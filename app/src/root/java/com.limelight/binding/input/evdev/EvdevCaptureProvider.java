package com.limelight.binding.input.evdev;

import android.app.Activity;
import android.os.Build;
import android.os.Looper;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.input.capture.InputCaptureProvider;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class EvdevCaptureProvider extends InputCaptureProvider {

    private final EvdevListener listener;
    private final String libraryPath;

    private boolean shutdown = false;
    private InputStream evdevIn;
    private OutputStream evdevOut;
    private Process su;
    private ServerSocket servSock;
    private Socket evdevSock;
    private Activity activity;
    private boolean started = false;
    private volatile boolean focusActive = true;
    private volatile EvdevReportDispatcher reportDispatcher;

    private static final byte UNGRAB_REQUEST = 1;
    private static final byte REGRAB_REQUEST = 2;

    private final Thread handlerThread = new Thread() {
        @Override
        public void run() {
            // Bind a local listening socket for evdevreader to connect to
            try {
                servSock = new ServerSocket(0, 1);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            final String evdevReaderCmd = libraryPath+File.separatorChar+"libevdev_reader.so "+servSock.getLocalPort();

            // On Nougat and later, we'll need to pass the command directly to SU.
            // Writing to SU's input stream after it has started doesn't seem to work anymore.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Launch evdev_reader directly via SU
                try {
                    su = new ProcessBuilder("su", "-c", evdevReaderCmd).start();
                } catch (IOException e) {
                    reportDeviceNotRooted();
                    e.printStackTrace();
                    return;
                }
            }
            else {
                // Launch a SU shell on Marshmallow and earlier
                ProcessBuilder builder = new ProcessBuilder("su");
                builder.redirectErrorStream(true);

                try {
                    su = builder.start();
                } catch (IOException e) {
                    reportDeviceNotRooted();
                    e.printStackTrace();
                    return;
                }

                // Start evdevreader
                DataOutputStream suOut = new DataOutputStream(su.getOutputStream());
                try {
                    suOut.writeChars(evdevReaderCmd+"\n");
                } catch (IOException e) {
                    reportDeviceNotRooted();
                    e.printStackTrace();
                    return;
                }
            }

            // Wait for evdevreader's connection
            LimeLog.info("Waiting for EvdevReader connection to port "+servSock.getLocalPort());
            try {
                evdevSock = servSock.accept();
                evdevIn = evdevSock.getInputStream();
                evdevOut = evdevSock.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            LimeLog.info("EvdevReader connected from port "+evdevSock.getPort());
            reportDispatcher = new EvdevReportDispatcher(listener);
            if (!focusActive || !isCapturing || isCursorVisible) {
                reportDispatcher.setAccepting(false);
                // evdev_reader starts in grab mode. If focus/capture state changed
                // while its socket was connecting, immediately bring the helper
                // into the same disabled state as the Java dispatcher.
                try {
                    evdevOut.write(UNGRAB_REQUEST);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }

            while (!isInterrupted() && !shutdown) {
                EvdevEvent event;
                try {
                    event = EvdevReader.read(evdevIn);
                } catch (IOException e) {
                    event = null;
                }
                if (event == null) {
                    break;
                }

                // Note: The EvdevReader process already filters input events when grabbing
                // is not enabled, so we don't need to that here.

                reportDispatcher.accept(event);
            }
        }
    };

    public EvdevCaptureProvider(Activity activity, EvdevListener listener) {
        this.listener = listener;
        this.activity = activity;
        this.libraryPath = activity.getApplicationInfo().nativeLibraryDir;
    }

    private void reportDeviceNotRooted() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, "This device is not rooted - Mouse capture is unavailable", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void runInNetworkSafeContextSynchronously(Runnable runnable) {
        // This function is used to avoid Android's strict NetworkOnMainThreadException.
        // For our usage, it is highly unlikely to cause problems since we only do
        // write operations and only to localhost sockets.
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            Thread t = new Thread(runnable);
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                // The main thread should never be interrupted
                e.printStackTrace();
            }
        }
        else {
            // Run the runnable directly
            runnable.run();
        }
    }
    @Override
    public void showCursor() {
        super.showCursor();
        if (reportDispatcher != null) {
            reportDispatcher.setAccepting(false);
        }
        // This may be called on the main thread
        runInNetworkSafeContextSynchronously(new Runnable() {
            @Override
            public void run() {
                if (started && !shutdown && evdevOut != null) {
                    try {
                        evdevOut.write(UNGRAB_REQUEST);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    @Override
    public void hideCursor() {
        super.hideCursor();
        if (reportDispatcher != null && focusActive) {
            reportDispatcher.setAccepting(true);
        }
        // This may be called on the main thread
        runInNetworkSafeContextSynchronously(new Runnable() {
            @Override
            public void run() {
                // Send a request to regrab if we're already capturing
                if (focusActive && started && !shutdown && evdevOut != null) {
                    try {
                        evdevOut.write(REGRAB_REQUEST);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean focusActive) {
        this.focusActive = focusActive;
        if (reportDispatcher != null) {
            reportDispatcher.setAccepting(focusActive && isCapturing && !isCursorVisible);
        }

        runInNetworkSafeContextSynchronously(new Runnable() {
            @Override
            public void run() {
                if (started && !shutdown && evdevOut != null) {
                    try {
                        if (focusActive) {
                            if (isCapturing && !isCursorVisible) {
                                evdevOut.write(REGRAB_REQUEST);
                            }
                        }
                        else {
                            evdevOut.write(UNGRAB_REQUEST);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    @Override
    public void enableCapture() {
        // Publish the desired capture/cursor state before starting the worker.
        // Thread.start() then provides the happens-before edge needed by the
        // worker's initial state check after evdev_reader connects.
        super.enableCapture();

        if (!started) {
            // Start the handler thread if it's our first time
            // capturing
            started = true;
            startHandlerThread();
        }
    }

    protected void startHandlerThread() {
        handlerThread.start();
    }

    @Override
    public void destroy() {
        // We need to stop the process in this context otherwise
        // we could get stuck waiting on output from the process
        // in order to terminate it.
        //
        // This may be called on the main thread.

        if (!started) {
            return;
        }

        shutdown = true;
        handlerThread.interrupt();

        runInNetworkSafeContextSynchronously(new Runnable() {
            @Override
            public void run() {
                if (servSock != null) {
                    try {
                        servSock.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (evdevSock != null) {
                    try {
                        evdevSock.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (evdevIn != null) {
                    try {
                        evdevIn.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (evdevOut != null) {
                    try {
                        evdevOut.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

        if (su != null) {
            su.destroy();
        }

        try {
            handlerThread.join();
        } catch (InterruptedException ignored) {}
    }
}
