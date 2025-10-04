/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.glide.common.io;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Pair;

import com.bumptech.glide.load.data.StreamLocalUriFetcher;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.MediaUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

class DecryptableStreamLocalUriFetcher extends StreamLocalUriFetcher {

  private static final String TAG = Log.tag(DecryptableStreamLocalUriFetcher.class);

  private static final long TOTAL_PIXEL_SIZE_LIMIT = 200_000_000L; // 200 megapixels

  private final Context context;

  DecryptableStreamLocalUriFetcher(Context context, Uri uri) {
    super(context.getContentResolver(), uri);
    this.context = context;
  }

  @Override
  protected InputStream loadResource(Uri uri, ContentResolver contentResolver) throws FileNotFoundException {
    if (MediaUtil.hasVideoThumbnail(context, uri)) {
      Bitmap thumbnail = MediaUtil.getVideoThumbnail(context, uri, 1000);

      if (thumbnail != null) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        thumbnail.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        ByteArrayInputStream thumbnailStream = new ByteArrayInputStream(baos.toByteArray());
        thumbnail.recycle();
        return thumbnailStream;
      }
      if (PartAuthority.isAttachmentUri(uri) && MediaUtil.isVideoType(PartAuthority.getAttachmentContentType(context, uri))) {
        try {
          AttachmentId attachmentId = PartAuthority.requireAttachmentId(uri);
          Uri          thumbnailUri = PartAuthority.getAttachmentThumbnailUri(attachmentId);
          InputStream  thumbStream  = PartAuthority.getAttachmentThumbnailStream(context, thumbnailUri);
          if (thumbStream != null) {
            return thumbStream;
          }
        } catch (IOException e) {
          Log.i(TAG, "Failed to fetch thumbnail", e);
        }
      }
    }

    try {
      if (PartAuthority.isBlobUri(uri) && BlobProvider.isSingleUseMemoryBlob(uri)) {
        return PartAuthority.getAttachmentThumbnailStream(context, uri);
      } else if (isSafeSize(context, uri)) {
        return PartAuthority.getAttachmentThumbnailStream(context, uri);
      } else {
        throw new IOException("File dimensions are too large!");
      }
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
      throw new FileNotFoundException("PartAuthority couldn't load Uri resource.");
    }
  }

  private boolean isSafeSize(Context context, Uri uri) throws IOException {
    try {
      InputStream            stream      = PartAuthority.getAttachmentThumbnailStream(context, uri);
      Pair<Integer, Integer> dimensions  = BitmapUtil.getDimensions(stream);
      long                   totalPixels = (long) dimensions.first * dimensions.second;
      return totalPixels < TOTAL_PIXEL_SIZE_LIMIT;
    } catch (BitmapDecodingException e) {
      Long size = PartAuthority.getAttachmentSize(context, uri);
      return size != null && size < GlideStreamConfig.getMarkReadLimitBytes();
    }
  }
}
