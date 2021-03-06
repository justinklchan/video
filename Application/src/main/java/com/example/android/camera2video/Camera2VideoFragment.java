/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2video;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.legacy.app.FragmentCompat;
import androidx.core.app.ActivityCompat;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2VideoFragment extends Fragment
        implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback, SensorEventListener {

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    private static final String TAG = "Camera2VideoFragment";
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    SeekBar seekBar;

    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
    };
    Switch sw1;

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to record video
     */
    private Button mButtonVideo;

//    private Button mButtonPic;

    /**
     * A reference to the opened {@link android.hardware.camera2.CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * A reference to the current {@link android.hardware.camera2.CameraCaptureSession} for
     * preview.
     */
    private CameraCaptureSession mPreviewSession;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

    };

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * The {@link android.util.Size} of video recording.
     */
    private Size mVideoSize;

    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;

    /**
     * Whether the app is recording video now
     */
    private boolean mIsRecordingVideo;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;


    private HandlerThread imageReaderThread;
    private Handler imageReaderHandler;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
     */
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };
    private Integer mSensorOrientation;
    private String mNextVideoAbsolutePath;
    private CaptureRequest.Builder mPreviewBuilder;

    public static Camera2VideoFragment newInstance() {
        return new Camera2VideoFragment();
    }
    TextView angleView;

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
//            Image image = null;
//            try {
//                image = reader.acquireLatestImage();
//                if (image != null) {
//                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
//                    Bitmap bitmap = fromByteBuffer(buffer);
//                    image.close();
//                }
//            } catch (Exception e) {
//                Log.w("asdf", e.getMessage());
//            }
            Log.e("asdf","Image "+System.currentTimeMillis());
        }
    };

    Bitmap fromByteBuffer(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes, 0, bytes.length);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    Vibrator vv;
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        vv = (Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE);
        return inflater.inflate(R.layout.fragment_camera2_video, container, false);
    }

    TextView tv1;
    TextView rsize;
    CheckBox cb1;
    int requestCode;
    private int grantResults[];
    ImageView preview;
    private ContentResolver cResolver;
    private Window window;
    private int brightness;

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        preview = (ImageView)view.findViewById(R.id.preview);
        angleView = (TextView) view.findViewById(R.id.vibe2);
        sw1 = (Switch) view.findViewById(R.id.switch1);
        mButtonVideo = (Button) view.findViewById(R.id.video);
//        mButtonPic = (Button) view.findViewById(R.id.snap);
        tv1 = (TextView) view.findViewById(R.id.timer);
        rsize = (TextView) view.findViewById(R.id.rsize);
        cb1 = (CheckBox) view.findViewById(R.id.checkBox);
        mButtonVideo.setOnClickListener(this);
//        mButtonPic.setOnClickListener(this);
        view.findViewById(R.id.vibe).setOnClickListener(this);
        view.findViewById(R.id.vibe2).setOnClickListener(this);
        view.findViewById(R.id.stop).setOnClickListener(this);

        sw1.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    startPreview();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        if (!Settings.System.canWrite(getActivity())) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(Uri.parse("package:" + getActivity().getPackageName()));
            startActivity(intent);
        }

        //Get the content resolver
        cResolver = getActivity().getContentResolver();

        //Get the current window
        window = getActivity().getWindow();

        try {
            // To handle the auto
            Settings.System.putInt(cResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            //Get the current system brightness
            brightness = Settings.System.getInt(cResolver, Settings.System.SCREEN_BRIGHTNESS);
        }
        catch (Settings.SettingNotFoundException e) {
            //Throw an error case it couldn't be retrieved
            Log.e("Error", "Cannot access system brightness");
            e.printStackTrace();
        }

        //Set the system brightness using the brightness variable value
        Settings.System.putInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, brightness);
        //Get the current window attributes
        WindowManager.LayoutParams layoutpars = window.getAttributes();
        //Set the brightness of this window
        layoutpars.screenBrightness = 0;
        //Apply attribute changes to this window
        window.setAttributes(layoutpars);

        seekBar = view.findViewById(R.id.seekBar);
        seekBar.setMax(255);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Log.e("asdf",progress+"");
//                mPreviewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, (float)progress);

                //Set the system brightness using the brightness variable value
                Settings.System.putInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, brightness);
                //Get the current window attributes
                WindowManager.LayoutParams layoutpars = window.getAttributes();
                //Set the brightness of this window
                layoutpars.screenBrightness = progress / (float)255;
                //Apply attribute changes to this window
                window.setAttributes(layoutpars);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

//        final View htop = view.findViewById(R.id.htop);
//        final View hbottom = view.findViewById(R.id.hbottom);
//        final View vleft = view.findViewById(R.id.vleft);
//        final View vright = view.findViewById(R.id.vright);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        final SharedPreferences.Editor editor = prefs.edit();

        // populate the margins at first
//        ViewGroup.MarginLayoutParams htopParams = (ViewGroup.MarginLayoutParams) htop.getLayoutParams();
//        int hTopMargin = prefs.getInt("htop", htopParams.topMargin);
//        htopParams.setMargins(htopParams.leftMargin, hTopMargin, htopParams.rightMargin, htopParams.bottomMargin);
//
//        ViewGroup.MarginLayoutParams hbottomParams = (ViewGroup.MarginLayoutParams) hbottom.getLayoutParams();
//        int hBottomMargin = prefs.getInt("hbottom", hbottomParams.topMargin);
//        hbottomParams.setMargins(hbottomParams.leftMargin, hBottomMargin, hbottomParams.rightMargin, hbottomParams.bottomMargin);
//
//        ViewGroup.MarginLayoutParams vleftParams = (ViewGroup.MarginLayoutParams) vleft.getLayoutParams();
//        int vleftMargin = prefs.getInt("vleft", vleftParams.leftMargin);
//        vleftParams.setMargins(vleftMargin, vleftParams.topMargin, vleftParams.rightMargin, vleftParams.bottomMargin);
//
//        ViewGroup.MarginLayoutParams vrightParams = (ViewGroup.MarginLayoutParams) vright.getLayoutParams();
//        int vrightMargin = prefs.getInt("vright", vrightParams.leftMargin);
//        vrightParams.setMargins(vrightMargin, vrightParams.topMargin, vrightParams.rightMargin, vrightParams.bottomMargin);

        final int inc = 5;

        // set up the listeners
//        ImageView up1 = view.findViewById(R.id.up1);
//        up1.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) htop.getLayoutParams();
//                Log.e("out",params.width+","+params.topMargin);
//                params.setMargins(params.leftMargin, params.topMargin-inc, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("htop", params.topMargin-inc);
//                editor.commit();
//            }
//        });
//        ImageView down1 = view.findViewById(R.id.down1);
//        down1.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) htop.getLayoutParams();
////                Log.e("out",params.width+","+params.topMargin);
//                params.setMargins(params.leftMargin, params.topMargin+inc, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("htop", params.topMargin+inc);
//                editor.commit();
//            }
//        });
//
//        ImageView up2 = view.findViewById(R.id.up2);
//        up2.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) hbottom.getLayoutParams();
////                Log.e("out",params.width+","+params.topMargin);
//                params.setMargins(params.leftMargin, params.topMargin-inc, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("hbottom", params.topMargin-inc);
//                editor.commit();
//            }
//        });
//        ImageView down2 = view.findViewById(R.id.down2);
//        down2.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) hbottom.getLayoutParams();
////                Log.e("out",params.topMargin);
//                params.setMargins(params.leftMargin, params.topMargin+inc, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("hbottom", params.topMargin+inc);
//                editor.commit();
//            }
//        });
//
//        ImageView left1 = view.findViewById(R.id.left1);
//        left1.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) vleft.getLayoutParams();
////                Log.e("out",params.leftMargin);
//                params.setMargins(params.leftMargin-inc, params.topMargin, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("vleft", params.leftMargin-inc);
//                editor.commit();
//            }
//        });
//        ImageView right1 = view.findViewById(R.id.right1);
//        right1.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) vleft.getLayoutParams();
////                Log.e("out",params.leftMargin);
//                params.setMargins(params.leftMargin+inc, params.topMargin, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("vleft", params.leftMargin+inc);
//                editor.commit();
//            }
//        });
//
//        ImageView left2 = view.findViewById(R.id.left2);
//        left2.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) vright.getLayoutParams();
////                Log.e("out",params.leftMargin);
//                params.setMargins(params.leftMargin-inc, params.topMargin, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("vright", params.leftMargin-inc);
//                editor.commit();
//            }
//        });
//        ImageView right2 = view.findViewById(R.id.right2);
//        right2.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) vright.getLayoutParams();
////                Log.e("out",params.leftMargin);
//                params.setMargins(params.leftMargin+inc, params.topMargin, params.rightMargin, params.bottomMargin);
//
//                editor.putInt("vright", params.leftMargin+inc);
//                editor.commit();
//            }
//        });

        mSensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
        mRot = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        mSensorManager.registerListener(this, mRot, SensorManager.SENSOR_DELAY_FASTEST);

        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO},requestCode);
        onRequestPermissionsResult(requestCode,new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO}, grantResults);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.video: {
                if (mIsRecordingVideo) {
                    stopRecordingVideo();
                } else {
                    startRecordingVideo();
                }
                break;
            }
            case R.id.info: {
                Activity activity = getActivity();
                if (null != activity) {
                    new AlertDialog.Builder(activity)
                            .setMessage(R.string.intro_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                break;
            }
            case R.id.vibe: {
                Log.e("test","vibe "+ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.VIBRATE));
//                vv.vibrate(VibrationEffect.createOneShot(60*1000, 255));
                vv.vibrate(VibrationEffect.createWaveform(new long[]{1000,1000}, 0));
                break;
            }
            case R.id.vibe2: {
                Log.e("test","vibe2 "+ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.VIBRATE));
                vv.vibrate(VibrationEffect.createWaveform(new long[]{1000,1000}, 0));
                break;
            }
            case R.id.stop: {
                Log.e("test","stop ");
                vv.cancel();
                break;
            }
//            case R.id.snap:
//                Log.e("test","snap");
//                takescreenshot();
//                snap();
//                break;
//            case R.id.flash: {
//                Log.e("test","flash "+ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.VIBRATE));
//                try {
//                    manager.setTorchMode(cameraId, true);
//                }
//                catch(Exception e) {
//                    Log.e("test",e.getMessage());
//                }
//                break;
//            }
        }
    }

    public void takescreenshot() {
        View v = getActivity().getWindow().getDecorView().getRootView();
        v.setDrawingCacheEnabled(true);
        v.buildDrawingCache(true);

        Bitmap bb = v.getDrawingCache();
        Bitmap bitmap = Bitmap.createBitmap(bb);
        v.setDrawingCacheEnabled(false);

        String out = MediaStore.Images.Media.insertImage(
                getActivity().getContentResolver(), bitmap, "", "");  // Saves the image.

        String path = convertMediaUriToPath(Uri.parse(out));

        File file = new File(path);

        Uri photoURI = FileProvider.getUriForFile(getContext(),
                getContext().getPackageName() + ".provider", file);

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(photoURI, "image/*");
        startActivity(intent);
    }

    protected String convertMediaUriToPath(Uri uri) {
        String[] proj = {MediaStore.Images.Media.DATA};
        Cursor cursor = getActivity().getContentResolver().query(uri, proj, null, null, null);
        int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        String path = cursor.getString(column_index);
        cursor.close();
        return path;
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationale(String[] permissions) {
        for (String permission : permissions) {
            if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Requests permissions needed for recording video.
     */
    private void requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.length == VIDEO_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        break;
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    CameraManager manager;
    String cameraId;

    /**
     * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
     */
    CameraCharacteristics characteristics;
    @SuppressWarnings("MissingPermission")
    private void openCamera(int width, int height) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
            return;
        }
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) {
            return;
        }
        manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            mCameraOpenCloseLock.release();
            if (!mCameraOpenCloseLock.tryAcquire(10000, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cameraId = manager.getCameraIdList()[0];

            // Choose the sizes for camera preview and video recording
            characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);

            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            configureTransform(width, height);
            mMediaRecorder = new MediaRecorder();
//            manager.setTorchMode(cameraId,true);
            manager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    public void buildRequest() {
        Log.e("out","build request "+sw1.isChecked());
        if (sw1.isChecked()) {
            mPreviewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
        }
        else {
            mPreviewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        }
        mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
        mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

        // S9
//        mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO);
//        mPreviewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);

        // S8
        mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        mPreviewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 100f);
    }

    private ImageReader mImageReader;
    Surface previewSurface;

    /**
     * Start the camera preview.
     */
    private void startPreview() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            closePreviewSession();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            ///////////////////////////////////////////////////////////////////////// new code
//            mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(),
//                    ImageFormat.YUV_420_888, 30);
            Log.e("sizes","video "+mVideoSize.getWidth()+","+mVideoSize.getHeight());
            Log.e("sizes","preview"+mPreviewSize.getWidth()+","+mPreviewSize.getHeight());
//            mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(),
//                    ImageFormat.YUV_420_888, 30);
//            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, imageReaderHandler);

            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            buildRequest();

            previewSurface = new Surface(texture);
            mPreviewBuilder.addTarget(previewSurface);

//            mPreviewBuilder.addTarget(mImageReader.getSurface());
            //////////////////////////////////////////////////////////////////////////////////
            mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mPreviewSession = session;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Activity activity = getActivity();
                            if (null != activity) {
                                Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Update the camera preview. {@link #startPreview()} needs to be called in advance.
     */
    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();

//            imageReaderThread = new HandlerThread("imageReaderThread");
//            imageReaderThread.start();
//
//            imageReaderHandler = new Handler(imageReaderThread.getLooper());
            CameraCaptureSession.CaptureCallback listener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);

                    if (!mIsRecordingVideo && cb1.isChecked()) {
                        mCounter += 1;

                        if (mCounter % 30 == 0) {
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    //s9
                                    int rgbi = 220;
                                    int nw = 500;
                                    int nh = 500;
                                    int si = 200;
                                    int sj = 450;
                                    //s8
//                                    int rgbi = 220;
//                                    int nw = 500;
//                                    int nh = 500;
//                                    int si = 700;
//                                    int sj = 650;

                                    float rmin = (float) (rgbi / 255.0);
                                    float rmax = (float) (255 / 255.0);
                                    float gmin = (float) (rgbi / 255.0);
                                    float gmax = (float) (255 / 255.0);
                                    float bmin = (float) (rgbi / 255.0);
                                    float bmax = (float) (255 / 255.0);
                                    long start = System.currentTimeMillis();
                                    Bitmap bm = mTextureView.getBitmap();
                                    int[] pixels = new int[bm.getWidth() * bm.getHeight()];
                                    int cc = 0;
                                    int rcounter = 0;
                                    for (int i = si; i < si + nw; i++) {
                                        for (int j = sj; j < sj + nh; j++) {
                                            Color c = Color.valueOf(bm.getPixel(i, j));
                                            if (c.red() >= rmin && c.red() <= rmax &&
                                                    c.green() >= gmin && c.green() <= gmax &&
                                                    c.blue() >= bmin && c.blue() <= bmax) {
                                                pixels[cc++] = Color.rgb(0, 0, 0);
                                                rcounter += 1;
                                            } else {
                                                //                                            pixels[cc++] = Color.rgb(255,0,0);
                                                pixels[cc++] = bm.getPixel(i, j);
                                            }
                                        }
                                    }

                                    Bitmap bitmap = Bitmap.createBitmap(pixels, nw, nh, bm.getConfig());

                                    preview.setImageBitmap(RotateBitmap(bitmap,0));
                                    rsize.setText(rcounter + "");
                                    Log.e("done", (System.currentTimeMillis() - start) + "");
                                }
                            });
                        }
                    }
                }
            };

            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), listener, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public static Bitmap RotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    double mCounter = 0;

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
//        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
//        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF);
//        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_AF_MODE_MACRO);
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }
    public String angPath;
    private void setUpMediaRecorder() throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            String ts = System.currentTimeMillis()+"";
            mNextVideoAbsolutePath = getVideoFilePath(getActivity(),ts);
            angPath = getAngFilePath(ts);
        }
//        mMediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED;
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(5000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setAudioSamplingRate(24000);
        mMediaRecorder.setAudioEncodingBitRate(64000);

        mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(MediaRecorder mr, int what, int extra) {
                Log.e("justin","media recorder on info"+what+","+extra);
//                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
//                }
            }
        });
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        mMediaRecorder.prepare();

    }

    private String getVideoFilePath(Context context, String ts) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + ts + ".mp4";
    }

    private String getAngFilePath(String ts) {
        String dir = getActivity().getExternalFilesDir(null).toString();
        return dir+File.separator+ts+".txt";
    }

    CountUpTimer timer;

    private void startRecordingVideo() {
        ang1 = new LinkedList<>();
        ang2 = new LinkedList<>();
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            timer = new CountUpTimer((60*61) * 1000) {
                public void onTick(int second) {
//                    TextView tv9 = .findViewById(R.id.textView9);
                    int raw = Integer.parseInt(String.valueOf(second));
                    int min = raw / 60;
                    int sec = raw % 60;
                    String tval = "";
                    if (sec < 10) {
                        tval=(min + ":0" + sec);
                    } else {
                        tval=(min + ":" + sec);
                    }
                    if (tv1!=null) {
                        tv1.setText(tval);
                    }
                    if (second > (60*60)-1) {
                        stopRecordingVideo();
                        vv.cancel();
                    }
//                    Log.e("out",second+"");
//                    Constants.timerVal = tval;
                }
            };
            timer.start();

            closePreviewSession();
            setUpMediaRecorder();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            buildRequest();

            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

//            mImageReader = ImageReader.newInstance(mVideoSize.getWidth(), mVideoSize.getHeight(),
//                    ImageFormat.YUV_420_888, 30);
//            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, imageReaderHandler);
//            mPreviewBuilder.addTarget(mImageReader.getSurface());

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // UI
                            mButtonVideo.setText(R.string.stop);
                            mIsRecordingVideo = true;

                            // Start recording
                            mMediaRecorder.start();
                        }
                    });
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Activity activity = getActivity();
                    if (null != activity) {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
        }
    }

    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    private void stopRecordingVideo() {
        // UI
        timer.cancel();
        mIsRecordingVideo = false;
        mButtonVideo.setText(R.string.record);
        // Stop recording
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        vv.cancel();
        Activity activity = getActivity();
        if (null != activity) {
            Toast.makeText(activity, "Video saved: " + mNextVideoAbsolutePath,
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Video saved: " + mNextVideoAbsolutePath);
        }
        mNextVideoAbsolutePath = null;
        startPreview();
        writeAngs();
    }

    public void writeAngs() {
        try {
            String dir = getActivity().getExternalFilesDir(null).toString();

            File path = new File(dir);
            if (!path.exists()) {
                path.mkdir();
            }

            File file = new File(dir+File.separator+System.currentTimeMillis()+".txt");
            if(!file.exists()) {
                file.createNewFile();
            }
            BufferedWriter buf = new BufferedWriter(new FileWriter(file,false));
            for (int i = 0; i < ang1.size(); i++) {
                buf.append(ang1.get(i)+","+ang2.get(i));
                buf.newLine();
            }
            buf.flush();
            buf.close();
        } catch(Exception e) {
            Log.e("test",e.getMessage());
        }
    }

    private SensorManager mSensorManager;
    Sensor mRot;
    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(
                mRotationMatrix, event.values);

        // side to side
        SensorManager.remapCoordinateSystem(mRotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Y,
                mRotationMatrixOut1);
        float[] orientation1 = new float[3];
        SensorManager.getOrientation(mRotationMatrixOut1, orientation1);
        convertToDegrees(orientation1);

        DecimalFormat format = new DecimalFormat("##.##");
        String sidetoside = format.format(orientation1[2]);
        ang1.add(orientation1[2]);

        SensorManager.remapCoordinateSystem(mRotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                mRotationMatrixOut1);
        SensorManager.getOrientation(mRotationMatrixOut1, orientation1);
        convertToDegrees(orientation1);
        String updown = format.format(orientation1[2]);
        ang2.add(orientation1[2]);

        angleView.setText(sidetoside+"\n"+updown);
    }

    LinkedList<Float>ang1 = new LinkedList<>();
    LinkedList<Float>ang2 = new LinkedList<>();

    private void convertToDegrees(float[] vector) {
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float)Math.toDegrees(vector[i]);
        }
    }
    float[] mRotationMatrix = new float[16];
    float[] mRotationMatrixOut1 = new float[16];

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    public static class ErrorDialog extends DialogFragment {
        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }
    }

    public static class ConfirmationDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.permission_request)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FragmentCompat.requestPermissions(parent, VIDEO_PERMISSIONS,
                                    REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    parent.getActivity().finish();
                                }
                            })
                    .create();
        }
    }
}
