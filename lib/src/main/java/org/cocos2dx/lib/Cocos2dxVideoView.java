/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2014-2016 Chukong Technologies Inc.
 * Copyright (c) 2017-2018 Xiamen Yaji Software Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cocos2dx.lib;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.graphics.Bitmap;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;

public class Cocos2dxVideoView extends SurfaceView {

    // ===========================================================
    // Internal classes and interfaces.
    // ===========================================================

    public interface OnVideoEventListener
    {
        void onVideoEvent(int tag,int event);
    }

    private enum State{
        IDLE,
        ERROR,
        INITIALIZED,
        PREPARING,
        PREPARED,
        STARTED,
        PAUSED,
        STOPPED,
        PLAYBACK_COMPLETED,
    }

    // ===========================================================
    // Constants
    // ===========================================================
    private static final String AssetResourceRoot = "@assets/";

    // ===========================================================
    // Fields
    // ===========================================================

    private String TAG = "Cocos2dxVideoView";

    private Uri         mVideoUri;
    private int         mDuration;

    private State mCurrentState = State.IDLE;

    // All the stuff we need for playing and showing a video
    private SurfaceHolder mSurfaceHolder = null;
    private MediaPlayer mMediaPlayer = null;
    private int         mVideoWidth = 0;
    private int         mVideoHeight = 0;

    private OnVideoEventListener mOnVideoEventListener;

    // recording the seek position while preparing
    private int         mSeekWhenPrepared = 0;

    protected Cocos2dxActivity mCocos2dxActivity = null;

    protected int mViewLeft = 0;
    protected int mViewTop = 0;
    protected int mViewWidth = 0;
    protected int mViewHeight = 0;

    protected int mVisibleLeft = 0;
    protected int mVisibleTop = 0;
    protected int mVisibleWidth = 0;
    protected int mVisibleHeight = 0;

    protected boolean mFullScreenEnabled = false;
    protected int mFullScreenWidth = 0;
    protected int mFullScreenHeight = 0;

    private boolean mIsAssetRouse = false;
    private String mVideoFilePath = null;

    private int mViewTag = 0;
    private boolean mKeepRatio = false;
    private boolean mMetaUpdated = false;

    private MediaMetadataRetriever mRetriever = null;
    private ByteBuffer mFrameBuf = null;
    private byte mPixels[] = null;
    private Bitmap mFrame = null;
    private Bitmap mCurFrame = null;
    private boolean mShowRaw = true;
    private Object mCopyListener = null;

    private Surface mSurface = null;

    private static final int PX_INVALID = 0;
    private static final int PX_ALPHA = 1;
    private static final int PX_ARGB_4444 = 2;
    private static final int PX_ARGB_8888 = 3;
    private static final int PX_HARDWARE = 4;
    private static final int PX_RGBA_F16 = 5;
    private static final int PX_RGB_565 = 6;

    // MediaPlayer will be released when surface view is destroyed, so should record the position,
    // and use it to play after MedialPlayer is created again.
    private int mPositionBeforeRelease = 0;

    // ===========================================================
    // Constructors
    // ===========================================================

    public Cocos2dxVideoView(Cocos2dxActivity activity,int tag) {
        super(activity);

        mViewTag = tag;
        mCocos2dxActivity = activity;
        initVideoView();
        setShowRawFrame(true); // Show mediaplayer window.
    }

    // ===========================================================
    // Getter & Setter
    // ===========================================================

    public void setVideoRect(int left, int top, int maxWidth, int maxHeight) {
        if (mViewLeft == left && mViewTop == top && mViewWidth == maxWidth && mViewHeight == maxHeight)
            return;

        mViewLeft = left;
        mViewTop = top;
        mViewWidth = maxWidth;
        mViewHeight = maxHeight;

        fixSize(mViewLeft, mViewTop, mViewWidth, mViewHeight);
    }

    public void setFullScreenEnabled(boolean enabled) {
        if (mFullScreenEnabled != enabled) {
            mFullScreenEnabled = enabled;
            fixSize();
        }
    }

    public void setVolume (float volume) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(volume, volume);
        }
    }

    public void setKeepRatio(boolean enabled) {
        mKeepRatio = enabled;
        fixSize();
    }

    public void setVideoURL(String url) {
        mIsAssetRouse = false;
        setVideoURI(Uri.parse(url), null);
    }

    public void setVideoFileName(String path) {
        if (path.startsWith(AssetResourceRoot)) {
            path = path.substring(AssetResourceRoot.length());
        }

        if (path.startsWith("/")) {
            mIsAssetRouse = false;
            setVideoURI(Uri.parse(path),null);
        }
        else {

            mVideoFilePath = path;
            mIsAssetRouse = true;
            setVideoURI(Uri.parse(path), null);
        }
    }

    public int getCurrentPosition() {
        if (! (mCurrentState == State.ERROR |
                mMediaPlayer == null) ) {
            return mMediaPlayer.getCurrentPosition();
        }

        return -1;
    }

    public int getDuration() {
        if (! (mCurrentState == State.IDLE ||
                mCurrentState == State.ERROR ||
                mCurrentState == State.INITIALIZED ||
                mMediaPlayer == null) ) {
            mDuration = mMediaPlayer.getDuration();
        }

        return mDuration;
    }

    /**
     * Register a callback to be invoked when some video event triggered.
     *
     * @param l The callback that will be run
     */
    public void setVideoViewEventListener(OnVideoEventListener l)
    {
        mOnVideoEventListener = l;
    }

    // ===========================================================
    // Overrides
    // ===========================================================

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(mVisibleWidth, mVisibleHeight);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if ((event.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
            this.sendEvent(EVENT_CLICKED);
        }
        return true;
    }

    // ===========================================================
    // Public functions
    // ===========================================================

    public void stop() {
        if (!(mCurrentState == State.IDLE || mCurrentState == State.INITIALIZED || mCurrentState == State.ERROR || mCurrentState == State.STOPPED)
                && mMediaPlayer != null) {
            mCurrentState = State.STOPPED;
            mMediaPlayer.stop();
            this.sendEvent(EVENT_STOPPED);

            // after the video is stop, it shall prepare to be playable again
            try {
                mMediaPlayer.prepare();
                this.showFirstFrame();
            } catch (Exception ex) {}
        }
    }

    public void stopPlayback() {
        this.release();
    }

    public void start() {
        if ((mCurrentState == State.PREPARED ||
                mCurrentState == State.PAUSED ||
                mCurrentState == State.PLAYBACK_COMPLETED) &&
                mMediaPlayer != null) {

            mCurrentState = State.STARTED;
            mMediaPlayer.start();
            this.sendEvent(EVENT_PLAYING);
        }
    }

    public void pause() {
        if ((mCurrentState == State.STARTED || mCurrentState == State.PLAYBACK_COMPLETED) &&
                mMediaPlayer != null) {
            mCurrentState = State.PAUSED;
            mMediaPlayer.pause();
            this.sendEvent(EVENT_PAUSED);
        }
    }

    public void seekTo(int ms) {
        if (mCurrentState == State.IDLE || mCurrentState == State.INITIALIZED ||
                mCurrentState == State.STOPPED || mCurrentState == State.ERROR ||
                mMediaPlayer == null) {
            return;
        }

        mMediaPlayer.seekTo(ms);
    }

    public void fixSize() {
        if (mFullScreenEnabled) {
            mFullScreenWidth = mCocos2dxActivity.getGLSurfaceView().getWidth();
            mFullScreenHeight = mCocos2dxActivity.getGLSurfaceView().getHeight();

            fixSize(0, 0, mFullScreenWidth, mFullScreenHeight);
        } else {
            fixSize(mViewLeft, mViewTop, mViewWidth, mViewHeight);
        }
    }

    public void fixSize(int left, int top, int width, int height) {
        if (mVideoWidth == 0 || mVideoHeight == 0) {
            mVisibleLeft = left;
            mVisibleTop = top;
            mVisibleWidth = width;
            mVisibleHeight = height;
        }
        else if (width != 0 && height != 0) {
            if (mKeepRatio && !mFullScreenEnabled) {
                if ( mVideoWidth * height  > width * mVideoHeight ) {
                    mVisibleWidth = width;
                    mVisibleHeight = width * mVideoHeight / mVideoWidth;
                } else if ( mVideoWidth * height  < width * mVideoHeight ) {
                    mVisibleWidth = height * mVideoWidth / mVideoHeight;
                    mVisibleHeight = height;
                }
                mVisibleLeft = left + (width - mVisibleWidth) / 2;
                mVisibleTop = top + (height - mVisibleHeight) / 2;
            } else {
                mVisibleLeft = left;
                mVisibleTop = top;
                mVisibleWidth = width;
                mVisibleHeight = height;
            }
        }
        else {
            mVisibleLeft = left;
            mVisibleTop = top;
            mVisibleWidth = mVideoWidth;
            mVisibleHeight = mVideoHeight;
        }

        getHolder().setFixedSize(mVisibleWidth, mVisibleHeight);

        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        lParams.leftMargin = mVisibleLeft;
        lParams.topMargin = mVisibleTop;
        setLayoutParams(lParams);
    }

    public int resolveAdjustedSize(int desiredSize, int measureSpec) {
        int result = desiredSize;
        int specMode = MeasureSpec.getMode(measureSpec);
        int specSize =  MeasureSpec.getSize(measureSpec);

        switch (specMode) {
            case MeasureSpec.UNSPECIFIED:
                /* Parent says we can be as big as we want. Just don't be larger
                 * than max size imposed on ourselves.
                 */
                result = desiredSize;
                break;

            case MeasureSpec.AT_MOST:
                /* Parent says we can be as big as we want, up to specSize.
                 * Don't be larger than specSize, and don't be larger than
                 * the max size imposed on ourselves.
                 */
                result = Math.min(desiredSize, specSize);
                break;

            case MeasureSpec.EXACTLY:
                // No choice. Do what we are told.
                result = specSize;
                break;
        }

        return result;
    }

    public byte[] getFrame() {
        if(mRetriever == null) {
            return null;
        } else {
            try {
                if (Build.VERSION.SDK_INT < 24) {
                    mFrame = mRetriever.getFrameAtTime(mMediaPlayer.getCurrentPosition() * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                } else {
                    if(mFrame == null) {
                        Bitmap firstFrame = mRetriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST);
                        mFrame = Bitmap.createBitmap(getWidth(), getHeight(), firstFrame.getConfig());
                    }

                    if (mCopyListener == null) {
                        mCopyListener = Cocos2dxReflectionHelper.createInstanceInterface("android.view.PixelCopy", "OnPixelCopyFinishedListener");
                    }

                    final Class clazz = Cocos2dxReflectionHelper.getClassByName("android.view.PixelCopy");
                    final Class intef = Cocos2dxReflectionHelper.getInterfaceByName("android.view.PixelCopy", "OnPixelCopyFinishedListener");
                    final Class types[] =  {SurfaceView.class, Bitmap.class, intef, Handler.class};
                    final Object params[] = {this, mFrame, mCopyListener, this.getHandler()};

                    Cocos2dxReflectionHelper.invokeInterfaceMethod(clazz, "request", types, params);
                }
            } catch (Exception e) {
                Log.e("Grab error", e.getStackTrace().toString());
                return mPixels;
            }

            if(mFrame == null) {
                return mPixels;
            }

            mCurFrame = mFrame;

            if(mFrameBuf == null) {
                mFrameBuf = ByteBuffer.allocate(mCurFrame.getByteCount());
            }
            mFrameBuf.position(0);
            mCurFrame.copyPixelsToBuffer(mFrameBuf);
            mPixels = mFrameBuf.array();

            return mPixels;
        }
    }

    public int getFrameChannel() throws NoSuchFieldException, IllegalAccessException {
        if (mCurFrame == null) {
            return 0;
        } else {
            Bitmap.Config cfg = mCurFrame.getConfig();
            if(Build.VERSION.SDK_INT > 26) {
                Field hardware = Bitmap.Config.class.getDeclaredField("HARDWARE");
                Field rgba_f16 = Bitmap.Config.class.getDeclaredField("RGBA_F16");
                if(hardware != null && cfg == hardware.get(null)) return PX_HARDWARE;
                if(rgba_f16 != null && cfg == rgba_f16.get(null)) return PX_RGBA_F16;
            }

            switch (cfg) {
                case ALPHA_8:
                    return PX_ALPHA;
                case ARGB_4444:
                    return PX_ARGB_4444;
                case ARGB_8888:
                    return PX_ARGB_8888;
                case RGB_565:
                    return PX_RGB_565;
                default:
                    return PX_INVALID;
            }
        }
    }

    public int getFrameWidth() {
        if (mCurFrame != null) {
            return mCurFrame.getWidth();
        } else {
            return 0;
        }
    }

    public int getFrameHeight() {
        if(mCurFrame != null) {
            return mCurFrame.getHeight();
        } else {
            return 0;
        }
    }

    public void setShowRawFrame(boolean show) {
        if(mShowRaw == show) { // Now show state == Before show state
            return; // Do nothing
        }
        setTranslationY(0); // Show view
        if(!show) {
            setTranslationY(-1000); // Hide view
        }
        mShowRaw = show;
    }

    // ===========================================================
    // Private functions
    // ===========================================================

    private void initVideoView() {
        mVideoWidth = 0;
        mVideoHeight = 0;
        getHolder().addCallback(mSHCallback);
        //Fix issue#11516:Can't play video on Android 2.3.x
        getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        setFocusable(true);
        setFocusableInTouchMode(true);
        mCurrentState = State.IDLE;
    }

    /**
     * @hide
     */
    private void setVideoURI(Uri uri, Map<String, String> headers) {
        mVideoUri = uri;
        mVideoWidth = 0;
        mVideoHeight = 0;
    }

    private void openVideo() {
        if (mSurfaceHolder == null) {
            // not ready for playback just yet, will try again later
            return;
        }
        if (mIsAssetRouse) {
            if(mVideoFilePath == null)
                return;
        } else if(mVideoUri == null) {
            return;
        }

        this.pausePlaybackService();

        try {
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setOnPreparedListener(mPreparedListener);
            mMediaPlayer.setOnCompletionListener(mCompletionListener);
            mMediaPlayer.setOnErrorListener(mErrorListener);
            mMediaPlayer.setDisplay(mSurfaceHolder);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setScreenOnWhilePlaying(true);

            mRetriever = new MediaMetadataRetriever();
            mFrameBuf = null;
            mPixels = null;
            mCurFrame = null;
            mFrame = null;

            if (mIsAssetRouse) {
                AssetFileDescriptor afd = mCocos2dxActivity.getAssets().openFd(mVideoFilePath);
                mMediaPlayer.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
                mRetriever.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
            } else {
                mMediaPlayer.setDataSource(mVideoUri.toString());
                mRetriever.setDataSource(mVideoUri.toString(), new HashMap<String, String>());
            }
            mCurrentState = State.INITIALIZED;


            // Use Prepare() instead of PrepareAsync to make things easy.
            mMediaPlayer.prepare();
            this.showFirstFrame();

//            mMediaPlayer.prepareAsync();
        } catch (IOException ex) {
            Log.w(TAG, "Unable to open content: " + mVideoUri, ex);
            mCurrentState = State.ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            return;
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "Unable to open content: " + mVideoUri, ex);
            mCurrentState = State.ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            return;
        }
    }

    MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
        public void onPrepared(MediaPlayer mp) {
            mVideoWidth = mp.getVideoWidth();
            mVideoHeight = mp.getVideoHeight();

            if (mVideoWidth != 0 && mVideoHeight != 0) {
                fixSize();
            }

            if(!mMetaUpdated) {
                Cocos2dxVideoView.this.sendEvent(EVENT_META_LOADED);
                Cocos2dxVideoView.this.sendEvent(EVENT_READY_TO_PLAY);
                mMetaUpdated = true;
            }

            mCurrentState = State.PREPARED;
        }
    };

    private MediaPlayer.OnCompletionListener mCompletionListener =
        new MediaPlayer.OnCompletionListener() {
        public void onCompletion(MediaPlayer mp) {
            mCurrentState = State.PLAYBACK_COMPLETED;
            Cocos2dxVideoView.this.sendEvent(EVENT_COMPLETED);
        }
    };


    private static final int EVENT_PLAYING = 0;
    private static final int EVENT_PAUSED = 1;
    private static final int EVENT_STOPPED = 2;
    private static final int EVENT_COMPLETED = 3;
    private static final int EVENT_META_LOADED = 4;
    private static final int EVENT_CLICKED = 5;
    private static final int EVENT_READY_TO_PLAY = 6;

    private MediaPlayer.OnErrorListener mErrorListener =
        new MediaPlayer.OnErrorListener() {
        public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
            Log.d(TAG, "Error: " + framework_err + "," + impl_err);
            mCurrentState = State.ERROR;

            /* Otherwise, pop up an error dialog so the user knows that
             * something bad has happened. Only try and pop up the dialog
             * if we're attached to a window. When we're going away and no
             * longer have a window, don't bother showing the user an error.
             */
            if (getWindowToken() != null) {
                Resources r = mCocos2dxActivity.getResources();
                int messageId;

                if (framework_err == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
                    // messageId = com.android.internal.R.string.VideoView_error_text_invalid_progressive_playback;
                    messageId = r.getIdentifier("VideoView_error_text_invalid_progressive_playback", "string", "android");
                } else {
                    // messageId = com.android.internal.R.string.VideoView_error_text_unknown;
                    messageId = r.getIdentifier("VideoView_error_text_unknown", "string", "android");
                }

                int titleId = r.getIdentifier("VideoView_error_title", "string", "android");
                int buttonStringId = r.getIdentifier("VideoView_error_button", "string", "android");

                new AlertDialog.Builder(mCocos2dxActivity)
                        .setTitle(r.getString(titleId))
                        .setMessage(messageId)
                        .setPositiveButton(r.getString(buttonStringId),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        /* If we get here, there is no onError listener, so
                                         * at least inform them that the video is over.
                                         */
                                        Cocos2dxVideoView.this.sendEvent(EVENT_COMPLETED);
                                    }
                                })
                        .setCancelable(false)
                        .show();
            }
            return true;
        }
    };

    SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback()
    {
        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        }

        public void surfaceCreated(SurfaceHolder holder) {
            mSurfaceHolder = holder;
            Cocos2dxVideoView.this.openVideo();

            if (mPositionBeforeRelease > 0)
                mMediaPlayer.seekTo(mPositionBeforeRelease);
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            // after we return from this we can't use the surface any more
            mSurfaceHolder = null;
            mPositionBeforeRelease = getCurrentPosition();
            Cocos2dxVideoView.this.release();
        }
    };

    /*
     * release the media player in any state
     */
    private void release() {
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    private void showFirstFrame() {
        mMediaPlayer.seekTo(1);
    }

    private void sendEvent(int event) {
        if (this.mOnVideoEventListener != null) {
            this.mOnVideoEventListener.onVideoEvent(this.mViewTag, event);
        }
    }

    // Tell the music playback service to pause
    // REFINE: these constants need to be published somewhere in the framework.
    private void pausePlaybackService() {
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        mCocos2dxActivity.sendBroadcast(i);
    }
}
