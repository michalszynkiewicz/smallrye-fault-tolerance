package io.smallrye.faulttolerance.core.timeout;

import static io.smallrye.faulttolerance.core.timeout.TestInvocation.delayed;
import static io.smallrye.faulttolerance.core.timeout.TestInvocation.immediatelyReturning;
import static io.smallrye.faulttolerance.core.util.TestThread.runOnTestThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.Before;
import org.junit.Test;

import io.smallrye.faulttolerance.core.util.TestException;
import io.smallrye.faulttolerance.core.util.TestThread;
import io.smallrye.faulttolerance.core.util.barrier.Barrier;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 */
public class FutureTimeoutTest {
    private Barrier watcherTimeoutElapsedBarrier;
    private Barrier watcherExecutionInterruptedBarrier;

    private TestTimeoutWatcher timeoutWatcher;
    private Executor asyncExecutor = Executors.newFixedThreadPool(4);

    @Before
    public void setUp() {
        watcherTimeoutElapsedBarrier = Barrier.interruptible();
        watcherExecutionInterruptedBarrier = Barrier.interruptible();

        timeoutWatcher = new TestTimeoutWatcher(watcherTimeoutElapsedBarrier, watcherExecutionInterruptedBarrier);
    }

    @Test
    public void failOnLackOfExecutor() {
        TestInvocation<Future<String>> invocation = immediatelyReturning(() -> CompletableFuture.completedFuture("foobar"));
        Timeout<Future<String>> timeout = new Timeout<>(invocation, "test invocation", 1000, timeoutWatcher,
                null);
        assertThatThrownBy(() -> new AsyncTimeout<>(timeout, null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Executor must be set");
    }

    @Test
    public void immediatelyReturning_value() throws Exception {
        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(() -> CompletableFuture.completedFuture("foobar"));
        Future<String> future = testThread.await();
        assertThat(future.get()).isEqualTo("foobar");
        assertThat(timeoutWatcher.timeoutWatchWasCancelled()).isTrue();
    }

    @Test
    public void immediatelyReturning_exception() {
        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(() -> {
            throw new TestException();
        });
        assertThatThrownBy(testThread::await).isExactlyInstanceOf(TestException.class);

        assertThat(timeoutWatcher.timeoutWatchWasCancelled()).isTrue();
    }

    @Test
    public void delayed_value_notTimedOut() throws Exception {
        Barrier invocationDelayBarrier = Barrier.interruptible();

        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(() -> CompletableFuture.completedFuture("foobar"));
        Future<String> future = testThread.await();

        invocationDelayBarrier.open();
        assertThat(future.get()).isEqualTo("foobar");
        assertThat(future.isDone()).isEqualTo(true);
        assertThat(timeoutWatcher.timeoutWatchWasCancelled()).isTrue();
    }

    @Test
    public void delayed_value_timedOut() throws InterruptedException {
        Barrier invocationDelayBarrier = Barrier.interruptible();

        TestInvocation<Future<String>> invocation = delayed(invocationDelayBarrier,
                () -> CompletableFuture.completedFuture("foobar"));

        Timeout<Future<String>> timeout = new Timeout<>(invocation, "test invocation", 1000, timeoutWatcher,
                null);
        TestThread<Future<String>> testThread = runOnTestThread(new AsyncTimeout<>(timeout, asyncExecutor));
        watcherTimeoutElapsedBarrier.open();
        watcherExecutionInterruptedBarrier.await();

        assertThatThrownBy(testThread::await)
                .isExactlyInstanceOf(TimeoutException.class)
                .hasMessage("test invocation timed out");

        assertThat(timeoutWatcher.timeoutWatchWasCancelled()).isFalse();
    }

    @Test
    public void delayed_value_timedOutNoninterruptibly() throws InterruptedException {
        Barrier invocationDelayBarrier = Barrier.noninterruptible();

        TestInvocation<Future<String>> invocation = delayed(invocationDelayBarrier,
                () -> CompletableFuture.completedFuture("foobar"));

        Timeout<Future<String>> timeout = new Timeout<>(invocation, "test invocation", 1000,
                timeoutWatcher, null);
        TestThread<Future<String>> testThread = runOnTestThread(new AsyncTimeout<>(timeout, asyncExecutor));
        watcherTimeoutElapsedBarrier.open();
        watcherExecutionInterruptedBarrier.await();

        assertThatThrownBy(testThread::await)
                .isExactlyInstanceOf(TimeoutException.class)
                .hasMessage("test invocation timed out");
        assertThat(timeoutWatcher.timeoutWatchWasCancelled()).isFalse(); // watcher should not be canceled if it caused the stop

        invocationDelayBarrier.open();
    }

    @Test
    public void delayed_value_cancelled() throws InterruptedException {
        Barrier invocationStartBarrier = Barrier.interruptible();
        Barrier invocationDelayBarrier = Barrier.interruptible();

        TestInvocation<Future<String>> invocation = delayed(invocationStartBarrier, invocationDelayBarrier,
                () -> CompletableFuture.completedFuture("foobar"));

        Timeout<Future<String>> timeout = new Timeout<>(invocation, "test invocation", 1000,
                timeoutWatcher, null);
        TestThread<Future<String>> testThread = runOnTestThread(new AsyncTimeout<>(timeout, asyncExecutor));

        invocationStartBarrier.await();
        testThread.interrupt();

        assertThatThrownBy(testThread::await)
                .isExactlyInstanceOf(InterruptedException.class);
        assertThat(timeoutWatcher.timeoutWatchWasCancelled()).isFalse(); // watcher should not be canceled if it caused the stop

        invocationDelayBarrier.open();
    }

    @Test
    public void delayed_value_selfInterrupted() {
        Barrier delayBarrier = Barrier.interruptible();

        Callable<Future<String>> action = () -> {
            Thread.currentThread().interrupt();
            delayBarrier.await();
            return CompletableFuture.completedFuture("foobar");
        };
        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(action);

        delayBarrier.open();

        assertThatThrownBy(testThread::await).isExactlyInstanceOf(InterruptedException.class);
        assertThat(timeoutWatcher.timeoutWatchWasCancelled()).isTrue();
    }

    @Test
    public void immediate_value_nonInterruptibleCancelShouldBePropagated() throws Exception {
        Barrier delayBarrier = Barrier.interruptible();

        Callable<Future<String>> action = () -> CompletableFuture.supplyAsync(() -> {
            try {
                delayBarrier.await();
            } catch (InterruptedException e) {
                throw new CompletionException(e);
            }
            return "foobar";
        });
        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(action);

        Future<String> future = testThread.await();
        future.cancel(false);

        assertThat(future.isCancelled()).isTrue();
        delayBarrier.open();
    }

    @Test
    public void immediate_value_interruptibleCancelShouldBePropagated() throws Exception {
        Barrier delayBarrier = Barrier.interruptible();

        Callable<Future<String>> action = () -> CompletableFuture.supplyAsync(() -> {
            try {
                delayBarrier.await();
            } catch (InterruptedException e) {
                throw new CompletionException(e);
            }
            return "foobar";
        });
        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(action);

        Future<String> future = testThread.await();
        future.cancel(true);

        assertThat(future.isCancelled()).isTrue();
        // this changed with 10/12/2019 refactoring, preivously it was interrupted exception
        // looks okay though
        assertThatThrownBy(future::get).isExactlyInstanceOf(CancellationException.class);
        delayBarrier.open();
    }

    @Test
    public void delayed_value_timedGetRethrowsEventualError() throws Exception {
        RuntimeException exception = new RuntimeException("forced");

        Callable<Future<String>> action = () -> CompletableFuture.supplyAsync(() -> {
            throw exception;
        });
        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(action);

        Future<String> future = testThread.await();

        assertThatThrownBy(() -> future.get(1000, TimeUnit.MILLISECONDS))
                .isExactlyInstanceOf(ExecutionException.class)
                .hasCause(exception);
    }

    @Test
    public void delayed_value_getRethrowsError() throws Exception {
        RuntimeException exception = new RuntimeException("forced");

        Callable<Future<String>> action = () -> CompletableFuture.supplyAsync(() -> {
            throw exception;
        });

        TestThread<Future<String>> testThread = runAsyncTimeoutImmediately(action);

        Future<String> future = testThread.await();

        assertThatThrownBy(future::get)
                .isExactlyInstanceOf(ExecutionException.class)
                .hasCause(exception);
    }

    private TestThread<Future<String>> runAsyncTimeoutImmediately(Callable<Future<String>> action) {
        TestInvocation<Future<String>> invocation = immediatelyReturning(action);
        Timeout<Future<String>> timeout = new Timeout<>(invocation, "test invocation", 1000,
                timeoutWatcher, null);
        return runOnTestThread(new AsyncTimeout<>(timeout, asyncExecutor));
    }
}