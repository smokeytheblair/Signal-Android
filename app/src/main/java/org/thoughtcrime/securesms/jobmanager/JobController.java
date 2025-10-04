package org.thoughtcrime.securesms.jobmanager;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.jobmanager.persistence.ConstraintSpec;
import org.thoughtcrime.securesms.jobmanager.persistence.DependencySpec;
import org.thoughtcrime.securesms.jobmanager.persistence.FullSpec;
import org.thoughtcrime.securesms.jobmanager.persistence.JobSpec;
import org.thoughtcrime.securesms.jobmanager.persistence.JobStorage;
import org.thoughtcrime.securesms.jobs.MinimalJobSpec;
import org.thoughtcrime.securesms.util.Debouncer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Manages the queue of jobs. This is the only class that should write to {@link JobStorage} to
 * ensure consistency.
 */
class JobController {

  private static final String TAG = Log.tag(JobController.class);

  private static final Predicate<MinimalJobSpec> NO_PREDICATE = spec -> true;

  private final Application                application;
  private final JobStorage                 jobStorage;
  private final JobInstantiator            jobInstantiator;
  private final ConstraintInstantiator     constraintInstantiator;
  private final JobTracker                 jobTracker;
  private final Scheduler                  scheduler;
  private final Debouncer                  debouncer;
  private final Callback                   callback;
  private final Map<String, ActiveJobInfo> runningJobs;

  private final int                             minGeneralRunners;
  private final int                             maxGeneralRunners;
  private final long                            generalRunnerIdleTimeout;
  private final AtomicInteger                   nextRunnerId;
  private final List<Predicate<MinimalJobSpec>> reservedRunnerPredicates;

  @VisibleForTesting
  final AtomicBoolean runnersStarted = new AtomicBoolean(false);

  @VisibleForTesting
  final List<JobRunner> activeGeneralRunners;

  JobController(@NonNull Application application,
                @NonNull JobStorage jobStorage,
                @NonNull JobInstantiator jobInstantiator,
                @NonNull ConstraintInstantiator constraintInstantiator,
                @NonNull JobTracker jobTracker,
                @NonNull Scheduler scheduler,
                @NonNull Debouncer debouncer,
                @NonNull Callback callback,
                int minGeneralRunners,
                int maxGeneralRunners,
                long generalRunnerIdleTimeout,
                @NonNull List<Predicate<MinimalJobSpec>> reservedRunnerPredicates)
  {
    this.application              = application;
    this.jobStorage               = jobStorage;
    this.jobInstantiator          = jobInstantiator;
    this.constraintInstantiator   = constraintInstantiator;
    this.jobTracker               = jobTracker;
    this.scheduler                = scheduler;
    this.debouncer                = debouncer;
    this.callback                 = callback;
    this.runningJobs              = new HashMap<>();
    this.minGeneralRunners        = minGeneralRunners;
    this.maxGeneralRunners        = maxGeneralRunners;
    this.generalRunnerIdleTimeout = generalRunnerIdleTimeout;
    this.nextRunnerId             = new AtomicInteger(0);
    this.activeGeneralRunners     = new CopyOnWriteArrayList<>();
    this.reservedRunnerPredicates = new ArrayList<>(reservedRunnerPredicates);
  }

  @WorkerThread
  synchronized void init() {
    jobStorage.updateAllJobsToBePending();
    notifyAll();
  }

  synchronized void wakeUp() {
    notifyAll();
    maybeScaleUpRunners(() -> jobStorage.getEligibleJobCount(System.currentTimeMillis()));
  }

  @WorkerThread
  void submitNewJobChains(@NonNull List<List<List<Job>>> chains) {
    synchronized (this) {
      for (List<List<Job>> chain : chains) {
        submitNewJobChain(chain);
      }
    }
  }

  @WorkerThread
  void submitNewJobChain(@NonNull List<List<Job>> chain) {
    synchronized (this) {
      chain = Stream.of(chain).filterNot(List::isEmpty).toList();

      if (chain.isEmpty()) {
        Log.w(TAG, "Tried to submit an empty job chain. Skipping.");
        return;
      }

      if (chainExceedsMaximumInstances(chain)) {
        Job solo = chain.get(0).get(0);
        jobTracker.onStateChange(solo, JobTracker.JobState.IGNORED);
        Log.w(TAG, JobLogger.format(solo, "Already at the max instance count. Factory limit: " + solo.getParameters().getMaxInstancesForFactory() + ", Queue limit: " + solo.getParameters().getMaxInstancesForQueue() + ". Skipping."));
        return;
      }

      if (chainContainsUnsatisfiableConditions(chain)) {
        throw new AssertionError("Unsatisfiable conditions found in job chain!");
      }

      insertJobChain(chain);
      scheduleJobs(chain.get(0));
    }

    // We have no control over what happens in jobs' onSubmit method, so we drop our lock to reduce the possibility of a deadlock
    triggerOnSubmit(chain);

    synchronized (this) {
      notifyAll();
      maybeScaleUpRunners(() -> jobStorage.getEligibleJobCount(System.currentTimeMillis()));
    }
  }

  @WorkerThread
  void submitJobWithExistingDependencies(@NonNull Job job, @NonNull Collection<String> dependsOn, @Nullable String dependsOnQueue) {
    List<List<Job>> chain = Collections.singletonList(Collections.singletonList(job));

    synchronized (this) {
      if (chainExceedsMaximumInstances(chain)) {
        jobTracker.onStateChange(job, JobTracker.JobState.IGNORED);
        Log.w(TAG, JobLogger.format(job, "Already at the max instance count. Factory limit: " + job.getParameters().getMaxInstancesForFactory() + ", Queue limit: " + job.getParameters().getMaxInstancesForQueue() + ". Skipping."));
        return;
      }

      Set<String> allDependsOn = new HashSet<>(dependsOn);
      Set<String> aliveDependsOn = Stream.of(dependsOn)
                                         .filter(id -> jobStorage.getJobSpec(id) != null)
                                         .collect(Collectors.toSet());

      if (dependsOnQueue != null) {
        List<String> inQueue = Stream.of(jobStorage.getJobsInQueue(dependsOnQueue))
                                     .map(JobSpec::getId)
                                     .toList();

        allDependsOn.addAll(inQueue);
        aliveDependsOn.addAll(inQueue);
      }

      if (jobTracker.haveAnyFailed(allDependsOn)) {
        Log.w(TAG, "This job depends on a job that failed! Failing this job immediately.");
        List<Job> dependents = onFailure(job);
        job.setContext(application);
        job.onFailure();
        for (Job child : dependents) {
          child.markCascadingFailure();
          child.onFailure();
        }
        return;
      }

      FullSpec fullSpec = buildFullSpec(job, aliveDependsOn);
      jobStorage.insertJobs(Collections.singletonList(fullSpec));

      scheduleJobs(Collections.singletonList(job));
    }

    // We have no control over what happens in jobs' onSubmit method, so we drop our lock to reduce the possibility of a deadlock
    triggerOnSubmit(chain);

    synchronized (this) {
      notifyAll();
      maybeScaleUpRunners(() -> jobStorage.getEligibleJobCount(System.currentTimeMillis()));
    }
  }

  @WorkerThread
  <T extends Job> void submitJobs(@NonNull List<T> jobs) {
    List<Job> canRun = new ArrayList<>(jobs.size());

    synchronized (this) {
      for (Job job : jobs) {
        if (exceedsMaximumInstances(job)) {
          jobTracker.onStateChange(job, JobTracker.JobState.IGNORED);
          Log.w(TAG, JobLogger.format(job, "Already at the max instance count. Factory limit: " + job.getParameters().getMaxInstancesForFactory() + ", Queue limit: " + job.getParameters().getMaxInstancesForQueue() + ". Skipping."));
        } else {
          canRun.add(job);
        }
      }

      if (canRun.isEmpty()) {
        return;
      }

      List<FullSpec> fullSpecs = canRun.stream().map(it -> buildFullSpec(it, Collections.emptyList())).collect(java.util.stream.Collectors.toList());
      jobStorage.insertJobs(fullSpecs);

      scheduleJobs(canRun);
    }

    // We have no control over what happens in jobs' onSubmit method, so we drop our lock to reduce the possibility of a deadlock
    for (Job job : canRun) {
      job.setContext(application);
      job.onSubmit();
    }

    synchronized (this) {
      notifyAll();
      maybeScaleUpRunners(() -> jobStorage.getEligibleJobCount(System.currentTimeMillis()));
    }
  }

  @WorkerThread
  void cancelJob(@NonNull String id) {
    Job       inactiveJob           = null;
    List<Job> inactiveJobDependents = Collections.emptyList();

    synchronized (this) {
      ActiveJobInfo runningJob = runningJobs.get(id);

      if (runningJob != null) {
        Log.w(TAG, JobLogger.format(runningJob.job, "Canceling while running."));
        runningJob.job.cancel();
      } else {
        JobSpec jobSpec = jobStorage.getJobSpec(id);

        if (jobSpec != null) {
          inactiveJob = createJob(jobSpec, jobStorage.getConstraintSpecs(id));
          Log.w(TAG, JobLogger.format(inactiveJob, "Canceling while inactive."));
          Log.w(TAG, JobLogger.format(inactiveJob, "Job failed."));

          inactiveJob.cancel();
          inactiveJobDependents = onFailure(inactiveJob);
        } else {
          Log.w(TAG, "Tried to cancel JOB::" + id + ", but it could not be found.");
        }
      }
    }

    // We have no control over what happens in jobs' onFailure method, so we drop our lock to reduce the possibility of a deadlock
    if (inactiveJob != null) {
      inactiveJob.onFailure();
      Stream.of(inactiveJobDependents).forEach(Job::onFailure);
    }
  }

  @WorkerThread
  void cancelAllInQueue(@NonNull String queue) {
    List<JobSpec> jobsInQueue;
    synchronized (this) {
      jobsInQueue = jobStorage.getJobsInQueue(queue);
    }

    Stream.of(jobsInQueue)
          .map(JobSpec::getId)
          .forEach(this::cancelJob);
  }

  @WorkerThread
  synchronized void update(@NonNull JobUpdater updater) {
    jobStorage.transformJobs(updater::update);
    notifyAll();
  }

  @WorkerThread
  synchronized List<JobSpec> findJobs(@NonNull Predicate<JobSpec> predicate) {
    return jobStorage.getAllMatchingFilter(predicate);
  }

  @WorkerThread
  synchronized void onRetry(@NonNull Job job, long backoffInterval) {
    if (backoffInterval <= 0) {
      throw new IllegalArgumentException("Invalid backoff interval! " + backoffInterval);
    }

    int    nextRunAttempt = job.getRunAttempt() + 1;
    byte[] serializedData = job.serialize();

    jobStorage.updateJobAfterRetry(job.getId(), System.currentTimeMillis(), nextRunAttempt, backoffInterval, serializedData);
    jobTracker.onStateChange(job, JobTracker.JobState.PENDING);

    List<Constraint> constraints = Stream.of(jobStorage.getConstraintSpecs(job.getId()))
                                         .map(ConstraintSpec::getFactoryKey)
                                         .map(constraintInstantiator::instantiate)
                                         .toList();


    Log.i(TAG, JobLogger.format(job, "Scheduling a retry in " + backoffInterval + " ms."));
    scheduler.schedule(backoffInterval, constraints);

    notifyAll();
  }

  synchronized void onJobFinished(@NonNull Job job) {
    runningJobs.remove(job.getId());
  }

  @WorkerThread
  synchronized void onSuccess(@NonNull Job job, @Nullable byte[] outputData) {
    if (outputData != null) {
      List<JobSpec> updates = Stream.of(jobStorage.getDependencySpecsThatDependOnJob(job.getId()))
                                    .map(DependencySpec::getJobId)
                                    .map(jobStorage::getJobSpec)
                                    .map(jobSpec -> mapToJobWithInputData(jobSpec, outputData))
                                    .toList();

      jobStorage.updateJobs(updates);
    }

    jobStorage.deleteJob(job.getId());
    jobTracker.onStateChange(job, JobTracker.JobState.SUCCESS);
    notifyAll();
  }

  /**
   * @return The list of all dependent jobs that should also be failed.
   */
  @WorkerThread
  synchronized @NonNull List<Job> onFailure(@NonNull Job job) {
    List<Job> dependents = Stream.of(jobStorage.getDependencySpecsThatDependOnJob(job.getId()))
                                 .map(DependencySpec::getJobId)
                                 .map(jobStorage::getJobSpec)
                                 .withoutNulls()
                                 .map(jobSpec -> {
                                   List<ConstraintSpec> constraintSpecs = jobStorage.getConstraintSpecs(jobSpec.getId());
                                   return createJob(jobSpec, constraintSpecs);
                                 })
                                 .toList();

    List<Job> all = new ArrayList<>(dependents.size() + 1);
    all.add(job);
    all.addAll(dependents);

    jobStorage.deleteJobs(Stream.of(all).map(Job::getId).toList());
    Stream.of(all).forEach(j -> jobTracker.onStateChange(j, JobTracker.JobState.FAILURE));

    return dependents;
  }

  /**
   * Retrieves the next job that is eligible for execution. To be 'eligible' means that the job:
   *  - Has no dependencies
   *  - Has no unmet constraints
   *
   * @param predicate Filter for jobs to consider
   * @param timeoutMs Maximum time to wait for a job. If 0, waits indefinitely.
   * @return Job to execute, or null if the timeout is hit
   */
  @WorkerThread
  synchronized @Nullable Job pullNextEligibleJobForExecution(@NonNull Predicate<MinimalJobSpec> predicate, String runnerName, long timeoutMs) {
    try {
      Job job;
      long startTime = System.currentTimeMillis();

      while ((job = getNextEligibleJobForExecution(predicate)) == null) {
        if (runningJobs.isEmpty()) {
          debouncer.publish(callback::onEmpty);
        }

        if (timeoutMs > 0) {
          long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
          if (remainingTime <= 0) {
            return null;
          }
          wait(remainingTime);
        } else {
          wait();
        }
      }

      jobStorage.markJobAsRunning(job.getId(), System.currentTimeMillis());
      runningJobs.put(job.getId(), new ActiveJobInfo(job, runnerName, timeoutMs == 0));
      jobTracker.onStateChange(job, JobTracker.JobState.RUNNING);

      return job;
    } catch (InterruptedException e) {
      Log.e(TAG, "Interrupted.");
      throw new AssertionError(e);
    }
  }

  /**
   * Retrieves a string representing the state of the job queue. Intended for debugging.
   */
  @WorkerThread
  synchronized @NonNull String getDebugInfo() {
    List<JobSpec>        running      = runningJobs.keySet().stream().map(jobStorage::getJobSpec).collect(java.util.stream.Collectors.toList());
    List<JobSpec>        jobs         = jobStorage.debugGetJobSpecs(1000);
    List<ConstraintSpec> constraints  = jobStorage.debugGetConstraintSpecs(1000);
    List<DependencySpec> dependencies = jobStorage.debugGetAllDependencySpecs();
    String               additional   = jobStorage.debugAdditionalDetails();

    StringBuilder info = new StringBuilder();

    info.append("-- Running Jobs\n");
    if (!jobs.isEmpty()) {
      running.stream().forEach(j -> {
        ActiveJobInfo activeInfo  = Objects.requireNonNull(runningJobs.get(j.getId()));

        info.append("[").append(activeInfo.runnerName).append("] ").append(j.toString()).append('\n');
      });
    } else {
      info.append("None\n");
    }

    info.append("\n-- Jobs\n");
    if (!jobs.isEmpty()) {
      Stream.of(jobs).forEach(j -> info.append(j.toString()).append('\n'));
    } else {
      info.append("None\n");
    }

    info.append("\n-- Constraints\n");
    if (!constraints.isEmpty()) {
      Stream.of(constraints).forEach(c -> info.append(c.toString()).append('\n'));
    } else {
      info.append("None\n");
    }

    info.append("\n-- Dependencies\n");
    if (!dependencies.isEmpty()) {
      Stream.of(dependencies).forEach(d -> info.append(d.toString()).append('\n'));
    } else {
      info.append("None\n");
    }

    info.append("\n-- Additional Details\n");
    info.append("Runners started: ").append(runnersStarted.get()).append('\n');
    info.append("General runner count: ").append(activeGeneralRunners.size()).append('\n');
    info.append("Reserved runner count: ").append(reservedRunnerPredicates.size()).append("\n\n");

    if (additional != null) {
      info.append(additional).append('\n');
    } else {
      info.append("No job storage info.\n");
    }

    return info.toString();
  }

  synchronized boolean areQueuesEmpty(@NonNull Set<String> queueKeys) {
    return jobStorage.areQueuesEmpty(queueKeys);
  }

  synchronized boolean areFactoriesEmpty(@NonNull Set<String> factoryKeys) {
    return jobStorage.areFactoriesEmpty(factoryKeys);
  }

  /**
   * Initializes the dynamic JobRunner system with minimum threads.
   */
  @WorkerThread
  synchronized void startJobRunners() {
    Log.i(TAG, "Starting JobRunners. (Reserved: " + reservedRunnerPredicates.size() + ", MinGeneral: " + minGeneralRunners + ", MaxGeneral: " + maxGeneralRunners + ", GeneralIdleTimeout: " + generalRunnerIdleTimeout + " ms)");
    runnersStarted.set(true);

    int reservedId = 1;
    for (Predicate<MinimalJobSpec> predicate : reservedRunnerPredicates) {
      JobRunner runner = new JobRunner(application, JobRunner.generateName(reservedId++, true, true), this, predicate == null ? NO_PREDICATE : predicate, 0);
      runner.start();
      Log.i(TAG, "Spawned new runner " + runner.getName());
    }

    int coreId = 1;
    for (int i = 0; i < minGeneralRunners; i++) {
      spawnGeneralRunner(coreId++, 0);
    }

    maybeScaleUpRunners(() -> jobStorage.getEligibleJobCount(System.currentTimeMillis()));

    notifyAll();
  }

  /**
   * Scales up the number of {@link JobRunner}s to satisfy the number of eligible jobs, if needed.
   */
  @VisibleForTesting
  synchronized void maybeScaleUpRunners(IntSupplier eligibleJobCountSupplier) {
    if (!runnersStarted.get()) {
      return;
    }

    int eligibleJobCount            = eligibleJobCountSupplier.getAsInt();
    int activeRunners              = this.activeGeneralRunners.size();
    int maxPossibleRunnersToSpawn  = maxGeneralRunners - activeRunners;
    int runnersToCoverEligibleJobs = eligibleJobCount - activeRunners;
    int actualRunnersToSpawn       = Math.min(runnersToCoverEligibleJobs, maxPossibleRunnersToSpawn);

    if (actualRunnersToSpawn > 0) {
      Log.i(TAG, "Spawning " + actualRunnersToSpawn + " new JobRunner(s) to meet demand. (CurrentActive: " + activeRunners + ", EligibleJobs: " + eligibleJobCount + ", MaxAllowed: " + maxGeneralRunners + ")");

      for (int i = 0; i < actualRunnersToSpawn; i++) {
        spawnGeneralRunner(nextRunnerId.incrementAndGet(), generalRunnerIdleTimeout);
      }
    }
  }

  private synchronized void spawnGeneralRunner(int id, long timeOutMs) {
    JobRunner runner = new JobRunner(application, JobRunner.generateName(id, false, timeOutMs == 0), this, NO_PREDICATE, timeOutMs);
    runner.start();
    activeGeneralRunners.add(runner);

    Log.d(TAG, "Spawned new general runner " + runner.getName() + " (CurrentActive: " + activeGeneralRunners.size() + ")");
  }

  @VisibleForTesting
  synchronized void onRunnerTerminated(@NonNull JobRunner runner) {
    activeGeneralRunners.remove(runner);
    Log.i(TAG, runner.getName() + " terminated. (CurrentActive: " + activeGeneralRunners.size() + ")");
  }

  @WorkerThread
  private boolean chainExceedsMaximumInstances(@NonNull List<List<Job>> chain) {
    if (chain.size() == 1 && chain.get(0).size() == 1) {
      return exceedsMaximumInstances(chain.get(0).get(0));
    } else {
      return false;
    }
  }

  @WorkerThread
  private boolean chainContainsUnsatisfiableConditions(@NonNull List<List<Job>> chain) {
    int firstGlobalPriority = chain.get(0).get(0).getParameters().getGlobalPriority();
    for (List<Job> segment : chain) {
      for (Job job : segment) {
        if (job.getParameters().getGlobalPriority() > firstGlobalPriority) {
          return true;
        }
      }
    }

    return false;
  }

  @WorkerThread
  private boolean exceedsMaximumInstances(@NonNull Job job) {
    boolean exceedsFactory = job.getParameters().getMaxInstancesForFactory() != Job.Parameters.UNLIMITED &&
                             jobStorage.getJobCountForFactory(job.getFactoryKey()) >= job.getParameters().getMaxInstancesForFactory();

    if (exceedsFactory) {
      return true;
    }

    boolean exceedsQueue   = job.getParameters().getQueue() != null                                    &&
                             job.getParameters().getMaxInstancesForQueue() != Job.Parameters.UNLIMITED &&
                             jobStorage.getJobCountForFactoryAndQueue(job.getFactoryKey(), job.getParameters().getQueue()) >= job.getParameters().getMaxInstancesForQueue();

    return exceedsQueue;
  }

  @WorkerThread
  private void triggerOnSubmit(@NonNull List<List<Job>> chain) {
    Stream.of(chain)
          .forEach(list -> Stream.of(list).forEach(job -> {
            job.setContext(application);
            job.onSubmit();
          }));
  }

  @WorkerThread
  private void insertJobChain(@NonNull List<List<Job>> chain) {
    List<FullSpec> fullSpecs = new LinkedList<>();
    List<String>   dependsOn = Collections.emptyList();

    for (List<Job> jobList : chain) {
      for (Job job : jobList) {
        fullSpecs.add(buildFullSpec(job, dependsOn));
      }
      dependsOn = Stream.of(jobList).map(Job::getId).toList();
    }

    jobStorage.insertJobs(fullSpecs);
  }

  @WorkerThread
  private @NonNull FullSpec buildFullSpec(@NonNull Job job, @NonNull Collection<String> dependsOn) {
    job.setRunAttempt(0);

    JobSpec jobSpec = new JobSpec(job.getId(),
                                  job.getFactoryKey(),
                                  job.getParameters().getQueue(),
                                  System.currentTimeMillis(),
                                  job.getLastRunAttemptTime(),
                                  job.getNextBackoffInterval(),
                                  job.getRunAttempt(),
                                  job.getParameters().getMaxAttempts(),
                                  job.getParameters().getLifespan(),
                                  job.serialize(),
                                  null,
                                  false,
                                  job.getParameters().isMemoryOnly(),
                                  job.getParameters().getGlobalPriority(),
                                  job.getParameters().getQueuePriority(),
                                  job.getParameters().getInitialDelay());

    List<ConstraintSpec> constraintSpecs = Stream.of(job.getParameters().getConstraintKeys())
                                                 .map(key -> new ConstraintSpec(jobSpec.getId(), key, jobSpec.isMemoryOnly()))
                                                 .toList();

    List<DependencySpec> dependencySpecs = Stream.of(dependsOn)
                                                 .map(depends -> {
                                                   JobSpec dependsOnJobSpec = jobStorage.getJobSpec(depends);
                                                   boolean memoryOnly       = job.getParameters().isMemoryOnly() || (dependsOnJobSpec != null && dependsOnJobSpec.isMemoryOnly());

                                                   return new DependencySpec(job.getId(), depends, memoryOnly);
                                                 })
                                                 .toList();

    return new FullSpec(jobSpec, constraintSpecs, dependencySpecs);
  }

  @WorkerThread
  private void scheduleJobs(@NonNull List<Job> jobs) {
    for (Job job : jobs) {
      List<String>     constraintKeys = job.getParameters().getConstraintKeys();
      List<Constraint> constraints    = new ArrayList<>(constraintKeys.size());
      for (String key : constraintKeys) {
        constraints.add(constraintInstantiator.instantiate(key));
      }

      scheduler.schedule(job.getParameters().getInitialDelay(), constraints);
    }
  }

  @WorkerThread
  private @Nullable Job getNextEligibleJobForExecution(@NonNull Predicate<MinimalJobSpec> predicate) {
    JobSpec jobSpec = jobStorage.getNextEligibleJob(System.currentTimeMillis(), minimalJobSpec -> {
      if (!predicate.test(minimalJobSpec)) {
        return false;
      }

      List<ConstraintSpec> constraintSpecs = jobStorage.getConstraintSpecs(minimalJobSpec.getId());
      List<Constraint>     constraints     = Stream.of(constraintSpecs)
                                                   .map(ConstraintSpec::getFactoryKey)
                                                   .map(constraintInstantiator::instantiate)
                                                   .toList();

      return Stream.of(constraints).allMatch(Constraint::isMet);
    });

    if (jobSpec == null) {
      return null;
    }

    List<ConstraintSpec> constraintSpecs = jobStorage.getConstraintSpecs(jobSpec.getId());
    return createJob(jobSpec, constraintSpecs);
  }

  private @NonNull Job createJob(@NonNull JobSpec jobSpec, @NonNull List<ConstraintSpec> constraintSpecs) {
    Job.Parameters parameters = buildJobParameters(jobSpec, constraintSpecs);

    try {
      Job job = jobInstantiator.instantiate(jobSpec.getFactoryKey(), parameters, jobSpec.getSerializedData());

      job.setRunAttempt(jobSpec.getRunAttempt());
      job.setLastRunAttemptTime(jobSpec.getLastRunAttemptTime());
      job.setNextBackoffInterval(jobSpec.getNextBackoffInterval());
      job.setContext(application);

      return job;
    } catch (RuntimeException e) {
      Log.e(TAG, "Failed to instantiate job! Failing it and its dependencies without calling Job#onFailure. Crash imminent.");

      List<String> failIds = Stream.of(jobStorage.getDependencySpecsThatDependOnJob(jobSpec.getId()))
                                   .map(DependencySpec::getJobId)
                                   .toList();

      jobStorage.deleteJob(jobSpec.getId());
      jobStorage.deleteJobs(failIds);

      Log.e(TAG, "Failed " + failIds.size() + " dependent jobs.");

      throw e;
    }
  }

  private @NonNull Job.Parameters buildJobParameters(@NonNull JobSpec jobSpec, @NonNull List<ConstraintSpec> constraintSpecs) {
    return new Job.Parameters.Builder(jobSpec.getId())
                  .setCreateTime(jobSpec.getCreateTime())
                  .setLifespan(jobSpec.getLifespan())
                  .setMaxAttempts(jobSpec.getMaxAttempts())
                  .setQueue(jobSpec.getQueueKey())
                  .setConstraints(Stream.of(constraintSpecs).map(ConstraintSpec::getFactoryKey).toList())
                  .setInputData(jobSpec.getSerializedInputData())
                  .build();
  }

  private @NonNull JobSpec mapToJobWithInputData(@NonNull JobSpec jobSpec, @NonNull byte[] inputData) {
    return new JobSpec(jobSpec.getId(),
                       jobSpec.getFactoryKey(),
                       jobSpec.getQueueKey(),
                       jobSpec.getCreateTime(),
                       jobSpec.getLastRunAttemptTime(),
                       jobSpec.getNextBackoffInterval(),
                       jobSpec.getRunAttempt(),
                       jobSpec.getMaxAttempts(),
                       jobSpec.getLifespan(),
                       jobSpec.getSerializedData(),
                       inputData,
                       jobSpec.isRunning(),
                       jobSpec.isMemoryOnly(),
                       jobSpec.getGlobalPriority(),
                       jobSpec.getQueuePriority(),
                       jobSpec.getInitialDelay());
  }

  interface Callback {
    void onEmpty();
  }

  record ActiveJobInfo(
    @NonNull Job job,
    String runnerName,
    boolean coreRunner
  ) {}
}
