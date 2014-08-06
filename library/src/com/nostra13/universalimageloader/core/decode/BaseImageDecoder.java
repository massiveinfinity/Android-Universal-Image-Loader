/*******************************************************************************
 * Copyright 2011-2013 Sergey Tarasevich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.core.decode;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Matrix;
import android.os.Build;
import android.util.Log;
import com.mobilesolutionworks.grepcode.gallery3d.exif.ExifInterface;
import com.mobilesolutionworks.grepcode.gallery3d.exif.ExifTag;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.download.ImageDownloader.Scheme;
import com.nostra13.universalimageloader.utils.ImageSizeUtils;
import com.nostra13.universalimageloader.utils.IoUtils;
import com.nostra13.universalimageloader.utils.L;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes images to {@link Bitmap}, scales them to needed size
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ImageDecodingInfo
 * @since 1.8.3
 */
public class BaseImageDecoder implements ImageDecoder {

	protected static final String LOG_SUBSAMPLE_IMAGE = "Subsample original image (%1$s) to %2$s (scale = %3$d) [%4$s]";
	protected static final String LOG_SCALE_IMAGE = "Scale subsampled image (%1$s) to %2$s (scale = %3$.5f) [%4$s]";
	protected static final String LOG_ROTATE_IMAGE = "Rotate image on %1$d\u00B0 [%2$s]";
	protected static final String LOG_FLIP_IMAGE = "Flip image horizontally [%s]";
	protected static final String ERROR_CANT_DECODE_IMAGE = "Image can't be decoded [%s]";

	protected final boolean loggingEnabled;

	/**
	 * @param loggingEnabled Whether debug logs will be written to LogCat. Usually should match {@link
	 *                       com.nostra13.universalimageloader.core.ImageLoaderConfiguration.Builder#writeDebugLogs()
	 *                       ImageLoaderConfiguration.writeDebugLogs()}
	 */
	public BaseImageDecoder(boolean loggingEnabled) {
		this.loggingEnabled = loggingEnabled;
	}

	/**
	 * Decodes image from URI into {@link Bitmap}. Image is scaled close to incoming {@linkplain ImageSize target size}
	 * during decoding (depend on incoming parameters).
	 *
	 * @param decodingInfo Needed data for decoding image
	 * @return Decoded bitmap
	 * @throws IOException                   if some I/O exception occurs during image reading
	 * @throws UnsupportedOperationException if image URI has unsupported scheme(protocol)
	 */
	@Override
	public Bitmap decode(ImageDecodingInfo decodingInfo) throws IOException {
		Bitmap decodedBitmap;
		ImageFileInfo imageInfo;

		InputStream imageStream = getImageStream(decodingInfo);
		try {
			imageInfo = defineImageSizeAndRotation(imageStream, decodingInfo);
			imageStream = resetStream(imageStream, decodingInfo);
			Options decodingOptions = prepareDecodingOptions(imageInfo.imageSize, decodingInfo);
			decodedBitmap = BitmapFactory.decodeStream(imageStream, null, decodingOptions);
		} finally {
			IoUtils.closeSilently(imageStream);
		}

		if (decodedBitmap == null) {
			L.e(ERROR_CANT_DECODE_IMAGE, decodingInfo.getImageKey());
		} else {
			decodedBitmap = considerExactScaleAndOrientatiton(decodedBitmap, decodingInfo, imageInfo.exif.rotation,
					imageInfo.exif.flipHorizontal);
		}
		return decodedBitmap;
	}

	protected InputStream getImageStream(ImageDecodingInfo decodingInfo) throws IOException {
		return decodingInfo.getDownloader().getStream(decodingInfo.getImageUri(), decodingInfo.getExtraForDownloader());
	}

	protected ImageFileInfo defineImageSizeAndRotation(InputStream imageStream, ImageDecodingInfo decodingInfo)
			throws IOException {
		Options options = new Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeStream(imageStream, null, options);

        imageStream = resetStream(imageStream, decodingInfo);

		ExifInfo exif;
		String imageUri = decodingInfo.getImageUri();
        boolean b = canDefineExifParams(imageUri, options.outMimeType);

        Log.d("com.nostra13.universalimageloader.core.decode", "canDefineExifParams = " + b);
        Log.d("com.nostra13.universalimageloader.core.decode", "decodingInfo.shouldConsiderExifParams() = " + decodingInfo.shouldConsiderExifParams());
        if (decodingInfo.shouldConsiderExifParams() && canDefineExifParams(imageUri, options.outMimeType)) {
			exif = defineExifOrientation(imageUri, imageStream);
		} else {
            Log.d("com.nostra13.universalimageloader.core.decode", "no exif");
            exif = new ExifInfo();
		}
		return new ImageFileInfo(new ImageSize(options.outWidth, options.outHeight, exif.rotation), exif);
	}

	private boolean canDefineExifParams(String imageUri, String mimeType) {
        Log.d("com.nostra13.universalimageloader.core.decode", "mimeType = " + mimeType);
        Log.d("com.nostra13.universalimageloader.core.decode", "Scheme.ofUri(imageUri) = " + Scheme.ofUri(imageUri));
        Log.d("com.nostra13.universalimageloader.core.decode", "imageUri = " + imageUri);
		return "image/jpeg".equalsIgnoreCase(mimeType) && (Scheme.ofUri(imageUri) == Scheme.FILE || Scheme.ofUri(imageUri) == Scheme.HTTP);
	}

	@TargetApi(Build.VERSION_CODES.ECLAIR)
    protected ExifInfo defineExifOrientation(String imageUri, InputStream imageStream) {
        int rotation = 0;
        boolean flip = false;
        try
        {
            ExifInterface exifInterface = new ExifInterface();
            exifInterface.readExif(imageStream);

//            try
//            {
//                for (ExifTag tag : exifInterface.getAllTags())
//                {
//                    Log.d("com.nostra13.universalimageloader.core.decode", "tag = " + tag);
//                }
//            }
//            catch (Exception e)
//            {
//                Log.d("com.nostra13.universalimageloader.core.decode", "no exif tags?");
//                // e.printStackTrace();
//            }
//
//			ExifInterface exif = new ExifInterface(Scheme.FILE.crop(imageUri));
            ExifTag tag = exifInterface.getTag(ExifInterface.TAG_ORIENTATION);
            if (tag != null)
            {
                int exifOrientation = tag.getValueAsInt(ExifInterface.Orientation.TOP_LEFT);

                switch (exifOrientation)
                {
                    case ExifInterface.Orientation.TOP_RIGHT:
                        flip = true;
                    case ExifInterface.Orientation.TOP_LEFT:
                        rotation = 0;
                        break;
//				case ExifInterface.Orientation.ORIENTATION_TRANSVERSE:
//					flip = true;
                    case ExifInterface.Orientation.RIGHT_TOP:
                        rotation = 90;
                        break;
                    case ExifInterface.Orientation.BOTTOM_RIGHT:
                        flip = true;
                    case ExifInterface.Orientation.BOTTOM_LEFT:
                        rotation = 180;
                        break;
//				case ExifInterface.Orientation.ORIENTATION_TRANSPOSE:
//					flip = true;
                    case ExifInterface.Orientation.RIGHT_BOTTOM:
                        rotation = 270;
                        break;
                }
            }
        }
        catch (IOException e)
        {
            L.w("Can't read EXIF tags from file [%s]", imageUri);
        }

        Log.d("com.nostra13.universalimageloader.core.decode", "rotation = " + rotation);
		return new ExifInfo(rotation, flip);
	}

	protected Options prepareDecodingOptions(ImageSize imageSize, ImageDecodingInfo decodingInfo) {
		ImageScaleType scaleType = decodingInfo.getImageScaleType();
		int scale;
		if (scaleType == ImageScaleType.NONE) {
			scale = 1;
		} else if (scaleType == ImageScaleType.NONE_SAFE) {
			scale = ImageSizeUtils.computeMinImageSampleSize(imageSize);
		} else {
			ImageSize targetSize = decodingInfo.getTargetSize();
			boolean powerOf2 = scaleType == ImageScaleType.IN_SAMPLE_POWER_OF_2;
			scale = ImageSizeUtils.computeImageSampleSize(imageSize, targetSize, decodingInfo.getViewScaleType(), powerOf2);
		}
		if (scale > 1 && loggingEnabled) {
			L.d(LOG_SUBSAMPLE_IMAGE, imageSize, imageSize.scaleDown(scale), scale, decodingInfo.getImageKey());
		}

		Options decodingOptions = decodingInfo.getDecodingOptions();
		decodingOptions.inSampleSize = scale;
		return decodingOptions;
	}

	protected InputStream resetStream(InputStream imageStream, ImageDecodingInfo decodingInfo) throws IOException {
		try {
			imageStream.reset();
		} catch (IOException e) {
            IoUtils.closeSilently(imageStream);
			imageStream = getImageStream(decodingInfo);
		}
		return imageStream;
	}

	protected Bitmap considerExactScaleAndOrientatiton(Bitmap subsampledBitmap, ImageDecodingInfo decodingInfo,
			int rotation, boolean flipHorizontal) {
		Matrix m = new Matrix();
		// Scale to exact size if need
		ImageScaleType scaleType = decodingInfo.getImageScaleType();
		if (scaleType == ImageScaleType.EXACTLY || scaleType == ImageScaleType.EXACTLY_STRETCHED) {
			ImageSize srcSize = new ImageSize(subsampledBitmap.getWidth(), subsampledBitmap.getHeight(), rotation);
			float scale = ImageSizeUtils.computeImageScale(srcSize, decodingInfo.getTargetSize(), decodingInfo
					.getViewScaleType(), scaleType == ImageScaleType.EXACTLY_STRETCHED);
			if (Float.compare(scale, 1f) != 0) {
				m.setScale(scale, scale);

				if (loggingEnabled) {
					L.d(LOG_SCALE_IMAGE, srcSize, srcSize.scale(scale), scale, decodingInfo.getImageKey());
				}
			}
		}
		// Flip bitmap if need
		if (flipHorizontal) {
			m.postScale(-1, 1);

			if (loggingEnabled) L.d(LOG_FLIP_IMAGE, decodingInfo.getImageKey());
		}
		// Rotate bitmap if need
		if (rotation != 0) {
			m.postRotate(rotation);

			if (loggingEnabled) L.d(LOG_ROTATE_IMAGE, rotation, decodingInfo.getImageKey());
		}

		Bitmap finalBitmap = Bitmap.createBitmap(subsampledBitmap, 0, 0, subsampledBitmap.getWidth(), subsampledBitmap
				.getHeight(), m, true);
		if (finalBitmap != subsampledBitmap) {
			subsampledBitmap.recycle();
		}
		return finalBitmap;
	}

	protected static class ExifInfo {

		public final int rotation;
		public final boolean flipHorizontal;

		protected ExifInfo() {
			this.rotation = 0;
			this.flipHorizontal = false;
		}

		protected ExifInfo(int rotation, boolean flipHorizontal) {
			this.rotation = rotation;
			this.flipHorizontal = flipHorizontal;
		}
	}

	protected static class ImageFileInfo {

		public final ImageSize imageSize;
		public final ExifInfo exif;

		protected ImageFileInfo(ImageSize imageSize, ExifInfo exif) {
			this.imageSize = imageSize;
			this.exif = exif;
		}
	}
}