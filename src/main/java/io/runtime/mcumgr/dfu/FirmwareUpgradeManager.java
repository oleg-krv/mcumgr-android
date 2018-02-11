package io.runtime.mcumgr.dfu;

import android.util.Log;

import java.io.IOException;
import java.util.Date;

import io.runtime.mcumgr.McuManager;
import io.runtime.mcumgr.McuMgrResponse;
import io.runtime.mcumgr.exception.McuMgrErrorException;
import io.runtime.mcumgr.util.CBOR;
import io.runtime.mcumgr.mgrs.DefaultManager;
import io.runtime.mcumgr.mgrs.ImageManager;
import io.runtime.mcumgr.McuMgrCallback;
import io.runtime.mcumgr.exception.McuMgrException;
import io.runtime.mcumgr.McuMgrTransport;

// TODO Add retries for each step

public class FirmwareUpgradeManager extends McuManager
        implements ImageManager.ImageUploadCallback {

    private final static String TAG = "FirmwareUpgradeManager";

    private ImageManager mImageManager;
    private DefaultManager mDefaultManager;
    private FirmwareUpgradeCallback mCallback;
    private byte[] mImageData;
    private byte[] mHash;

    private State mState;
    private boolean mPaused = false;

    public FirmwareUpgradeManager(McuMgrTransport transport, byte[] imageData,
                                  FirmwareUpgradeCallback callback) {
        super(GROUP_PERUSER, transport);
        initFields(imageData, callback);
    }

    private void initFields(byte[] imageData, FirmwareUpgradeCallback callback) {
        mImageData = imageData;
        mCallback = callback;
        mState = State.NONE;
        mImageManager = McuManager.newImageManager(getTransporter());
        mDefaultManager = McuManager.newDefaultManager(getTransporter());
        mHash = ImageManager.getHashFromImage(imageData);
    }

    public synchronized void start() {
        if (mState != State.NONE) {
            Log.i(TAG, "Firmware upgrade is already in progress");
            return;
        }
        // Begin the upload
        mState = State.UPLOAD;
        mImageManager.upload(mImageData, this);
    }

    public synchronized void cancel() {
        cancelPrivate();
        mCallback.onCancel(mState);
    }

    private synchronized void cancelPrivate() {
        mState = State.NONE;
        mPaused = false;
        if (mResetPollThread != null) {
            mResetPollThread.interrupt();
        }
    }

    private synchronized void fail(McuMgrException error) {
        mCallback.onFail(mState, error);
        cancelPrivate();
    }

    public synchronized void pause() {
        if (mState.isInProgress()) {
            Log.d(TAG, "Pausing upgrade.");
            mPaused = true;
            if (mState == State.UPLOAD) {
                mImageManager.pauseUpload();
            }
        }
    }

    public synchronized void resume() {
        if (mPaused) {
            mPaused = false;
            currentState();
        }
    }

    public boolean isPaused() {
        return mPaused;
    }

    public State getState() {
        return mState;
    }

    public boolean isInProgress() {
        return mState.isInProgress();
    }

    private synchronized void currentState() {
        if (mPaused) {
            return;
        }
        switch (mState) {
            case NONE:
                return;
            case UPLOAD:
                mImageManager.continueUpload();
                break;
            case TEST:
                mImageManager.test(mHash, mTestCallback);
                break;
            case RESET:
                nextState();
                break;
            case CONFIRM:
                mImageManager.confirm(mHash, mConfirmCallback);
                break;
        }
    }

    private synchronized void nextState() {
        if (mPaused) {
            return;
        }
        State prevState = mState;
        switch (mState) {
            case NONE:
                return;
            case UPLOAD:
                mState = State.TEST;
                mImageManager.test(mHash, mTestCallback);
                break;
            case TEST:
                mState = State.RESET;
                mDefaultManager.reset(mResetCallback);
                break;
            case RESET:
                mState = State.CONFIRM;
                mImageManager.confirm(mHash, mConfirmCallback);
                break;
            case CONFIRM:
                mState = State.SUCCESS;
                break;
        }
        mCallback.onStateChanged(prevState, mState);
        if (mState == State.SUCCESS) {
            mCallback.onSuccess();
        }
    }

    //******************************************************************
    // CoAP Handlers
    //******************************************************************

    private McuMgrCallback mTestCallback = new McuMgrCallback() {
        @Override
        public void onResponse(McuMgrResponse response) {
            if (!response.isSuccess()) {
                fail(new McuMgrException("Test command failed!"));
                return;
            }
            if (response.getRc() != Code.OK) {
                Log.e(TAG, "Test failed due to Newt Manager error: " + response.getRc());
                fail(new McuMgrErrorException(response.getRc()));
                return;
            }
            try {
                ImageManager.StateResponse testResponse = CBOR.toObject(response.getPayload(),
                        ImageManager.StateResponse.class);
                Log.v(TAG, "Test response: " + CBOR.toString(testResponse));
                if (testResponse.images.length != 2) {
                    fail(new McuMgrException("Test response does not contain enough info"));
                    return;
                }
                if (!testResponse.images[1].pending) {
                    Log.e(TAG, "Tested image is not in a pending state.");
                    fail(new McuMgrException("Tested image is not in a pending state."));
                    return;
                }
                // Test image success, begin device reset
                nextState();
            } catch (IOException e) {
                e.printStackTrace();
                fail(new McuMgrException("Error parsing test response", e));
            }
        }

        @Override
        public void onError(McuMgrException e) {
            fail(e);
        }
    };

    private McuMgrCallback mResetCallback = new McuMgrCallback() {
        @Override
        public void onResponse(McuMgrResponse response) {
            if (!response.isSuccess()) {
                fail(new McuMgrException("Confirm command failed!"));
                return;
            }
            if (response.getRc() != Code.OK) {
                Log.e(TAG, "Reset failed due to Newt Manager error: " + response.getRc());
                fail(new McuMgrErrorException(response.getRc()));
                return;
            }
            // Begin polling the device to determine the reset
            mResetPollThread.start();
        }

        @Override
        public void onError(McuMgrException e) {
            fail(e);
        }
    };

    private McuMgrCallback mConfirmCallback = new McuMgrCallback() {
        @Override
        public void onResponse(McuMgrResponse response) {
            if (!response.isSuccess()) {
                fail(new McuMgrException("Confirm failed!"));
                return;
            }
            if (response.getRc() != Code.OK) {
                Log.e(TAG, "Confirm failed due to Newt Manager error: " + response.getRc());
                fail(new McuMgrErrorException(response.getRc()));
                return;
            }
            try {
                ImageManager.StateResponse confirmResponse = CBOR.toObject(response.getPayload(),
                        ImageManager.StateResponse.class);
                Log.v(TAG, "Confirm response: " + CBOR.toString(confirmResponse));

                if (confirmResponse.images.length == 0) {
                    fail(new McuMgrException("Test response does not contain enough info"));
                    return;
                }
                if (!confirmResponse.images[0].confirmed) {
                    Log.e(TAG, "Image is not in a confirmed state.");
                    fail(new McuMgrException("Image is not in a confirmed state."));
                    return;
                }
                // Confirm image success
                nextState();
            } catch (IOException e) {
                fail(new McuMgrException("Error parsing confirm response", e));
            }
        }

        @Override
        public void onError(McuMgrException e) {
            fail(e);
        }
    };

    //******************************************************************
    // Firmware Upgrade State
    //******************************************************************

    public enum State {
        NONE, UPLOAD, TEST, RESET, CONFIRM, SUCCESS;

        public boolean isInProgress() {
            if (this == UPLOAD || this == TEST || this == RESET || this == CONFIRM) {
                return true;
            } else {
                return false;
            }
        }
    }

    //******************************************************************
    // Poll Reset Runnable
    //******************************************************************

    private Thread mResetPollThread = new Thread(new Runnable() {
        @Override
        public void run() {
            int attempts = 0;
            try {
                // Wait for the device to reset before polling. The BLE disconnect callback will
                // take 20 seconds to trigger. TODO need to figure out a better way for UDP
                synchronized (this) {
                    wait(21000);
                }
                while (true) {
                    Log.d(TAG, "Calling image list...");
                    mImageManager.list(new McuMgrCallback() {
                        @Override
                        public synchronized void onResponse(McuMgrResponse response) {
                            if (mState == State.RESET) {
                                // Device has reset, begin confirm
                                nextState();
                                // Interrupt the thread
                                mResetPollThread.interrupt();
                            }
                        }

                        @Override
                        public void onError(McuMgrException e) {
                            // Do nothing...
                        }
                    });
                    if (attempts == 4) {
                        fail(new McuMgrException("Reset poller has reached attempt limit."));
                        return;
                    }
                    attempts++;
                    synchronized (this) {
                        wait(5000);
                    }
                }
            } catch (InterruptedException e) {
                // Do nothing...
            }
        }
    });

    //******************************************************************
    // Image Upload Callbacks
    //******************************************************************

    @Override
    public void onProgressChange(int bytesSent, int imageSize, Date ts) {
        mCallback.onUploadProgressChanged(bytesSent, imageSize, ts);
    }

    @Override
    public void onUploadFail(McuMgrException error) {
        mCallback.onFail(mState, error);
    }

    @Override
    public void onUploadFinish() {
        // Upload finished, move to next state
        nextState();
    }
}