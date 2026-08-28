package com.limelight.discovery;

import java.util.List;

import com.limelight.nvstream.mdns.MdnsComputer;
import com.limelight.nvstream.mdns.MdnsDiscoveryAgent;
import com.limelight.nvstream.mdns.MdnsDiscoveryListener;
import com.limelight.nvstream.mdns.NsdManagerDiscoveryAgent;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

public class DiscoveryService extends Service {

    private MdnsDiscoveryAgent discoveryAgent;
    private MdnsDiscoveryListener boundListener;

    public class DiscoveryBinder extends Binder {
        public void setListener(MdnsDiscoveryListener listener) {
            boundListener = listener;
        }

        public void startDiscovery(int queryIntervalMs) {
            discoveryAgent.startDiscovery(queryIntervalMs);
        }

        public void stopDiscovery() {
            discoveryAgent.stopDiscovery();
        }

        public List<MdnsComputer> getComputerSet() {
            return discoveryAgent.getComputerSet();
        }
    }

    @Override
    public void onCreate() {
        MdnsDiscoveryListener listener = new MdnsDiscoveryListener() {
            @Override
            public void notifyComputerAdded(MdnsComputer computer) {
                if (boundListener != null) {
                    boundListener.notifyComputerAdded(computer);
                }
            }

            @Override
            public void notifyDiscoveryFailure(Exception e) {
                if (boundListener != null) {
                    boundListener.notifyDiscoveryFailure(e);
                }
            }
        };

        // Android XR devices run Android 14 or newer, where NsdManager supports the complete
        // discovery contract and works correctly with environments that require mDNS proxying.
        discoveryAgent = new NsdManagerDiscoveryAgent(getApplicationContext(), listener);
    }

    private final DiscoveryBinder binder = new DiscoveryBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Stop any discovery session
        discoveryAgent.stopDiscovery();

        // Unbind the listener
        boundListener = null;
        return false;
    }
}
