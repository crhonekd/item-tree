package com.myxcomp.ice.xtree.messaging.dev;

import com.myxcomp.ice.xtree.messaging.ConnectionRecoveryListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StubConnectionExceptionListenerTest {

    @Test
    void simulateDisconnect_invokes_onConnectionLost_on_every_listener() {
        ConnectionRecoveryListener a = mock(ConnectionRecoveryListener.class);
        ConnectionRecoveryListener b = mock(ConnectionRecoveryListener.class);
        StubConnectionExceptionListener listener = new StubConnectionExceptionListener();
        listener.addRecoveryListener(a);
        listener.addRecoveryListener(b);

        listener.simulateDisconnect();

        verify(a).onConnectionLost("itemtree");
        verify(b).onConnectionLost("itemtree");
    }

    @Test
    void simulateRecovery_invokes_onConnectionRecovered_on_every_listener() {
        ConnectionRecoveryListener a = mock(ConnectionRecoveryListener.class);
        StubConnectionExceptionListener listener = new StubConnectionExceptionListener();
        listener.addRecoveryListener(a);

        listener.simulateRecovery();

        verify(a).onConnectionRecovered("itemtree");
    }

    @Test
    void throwing_listener_does_not_prevent_other_listeners_from_firing() {
        List<String> calls = new ArrayList<>();
        StubConnectionExceptionListener listener = new StubConnectionExceptionListener();
        listener.addRecoveryListener(new ConnectionRecoveryListener() {
            @Override public void onConnectionLost(String s) { throw new RuntimeException("boom"); }
            @Override public void onConnectionRecovered(String s) { throw new RuntimeException("boom"); }
        });
        listener.addRecoveryListener(new ConnectionRecoveryListener() {
            @Override public void onConnectionLost(String s) { calls.add("lost"); }
            @Override public void onConnectionRecovered(String s) { calls.add("recovered"); }
        });

        assertThatCode(listener::simulateDisconnect).doesNotThrowAnyException();
        assertThatCode(listener::simulateRecovery).doesNotThrowAnyException();
        assertThat(calls).containsExactly("lost", "recovered");
    }
}
