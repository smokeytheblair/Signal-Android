package org.whispersystems.signalservice.api.crypto;


import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public abstract class DigestingOutputStream extends FilterOutputStream {

  private final MessageDigest runningDigest;

  private byte[] digest;
  private long   totalBytesWritten = 0;

  public DigestingOutputStream(OutputStream outputStream) {
    super(outputStream);

    try {
      this.runningDigest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void write(byte[] buffer) throws IOException {
    runningDigest.update(buffer, 0, buffer.length);
    out.write(buffer, 0, buffer.length);
    totalBytesWritten += buffer.length;
  }

  public void write(byte[] buffer, int offset, int length) throws IOException {
    runningDigest.update(buffer, offset, length);
    out.write(buffer, offset, length);
    totalBytesWritten += length;
  }

  public void write(int b) throws IOException {
    runningDigest.update((byte)b);
    out.write(b);
    totalBytesWritten++;
  }

  public void close() throws IOException {
    digest = runningDigest.digest();
    out.close();
  }

  public byte[] getTransmittedDigest() {
    return digest;
  }

  public long getTotalBytesWritten() {
    return totalBytesWritten;
  }
}
