package io.smallrye.faulttolerance.core.timeout;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.FutureInvocationContext;
import io.smallrye.faulttolerance.core.FutureOrFailure;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 */
public class FutureTimeout<V> extends TimeoutBase<Future<V>, FutureInvocationContext<V>> {

    private final Executor asyncExecutor;

    public FutureTimeout(FaultToleranceStrategy<Future<V>, FutureInvocationContext<V>> delegate, String description,
            long timeoutInMillis, TimeoutWatcher watcher, MetricsRecorder metricsRecorder, Executor asyncExecutor) {
        super(delegate, description, timeoutInMillis, watcher, metricsRecorder);
        this.asyncExecutor = asyncExecutor;
        if (asyncExecutor == null) {
            throw new IllegalArgumentException("Async Future execution requires an asyncExecutor, none provided");
        }
    }

    @Override
    public Future<V> apply(FutureInvocationContext<V> context) throws Exception {
        FutureOrFailure<V> result = new FutureOrFailure<>();
        asyncExecutor.execute(
                () -> {
                    TimeoutExecution execution = new TimeoutExecution(Thread.currentThread(),
                            () -> result.timeout(timeoutException()), timeoutInMillis);

                    TimeoutWatch watch = watcher.schedule(execution);
                    if (context.getCancellator() != null) {
                        context.getCancellator().addCancelAction(
                                interrupt -> {
                                    watch.cancel();
                                    result.cancel(interrupt);
                                });
                    }

                    long start = System.nanoTime();

                    Exception exception = null;
                    boolean interrupted = false;
                    try {
                        Future<V> rawResult = delegate.apply(context);
                        if (watch.isRunning()) {
                            execution.finish(watch::cancel);
                        }
                        result.setDelegate(rawResult);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (Exception e) {
                        exception = e;
                    } finally {
                        // if the execution already timed out, this will be a noop
                        if (watch.isRunning()) {
                            execution.finish(watch::cancel);
                        }
                    }

                    if (Thread.interrupted()) {
                        // using `Thread.interrupted()` intentionally and unconditionally, because per MP FT spec, chapter 6.1,
                        // interruption status must be cleared when the method returns
                        interrupted = true;
                    }

                    if (interrupted && !execution.hasTimedOut()) {
                        exception = new InterruptedException();
                    }

                    long end = System.nanoTime();
                    if (execution.hasTimedOut()) {
                        metricsRecorder.timeoutTimedOut(end - start);
                        result.setFailure(timeoutException());
                    } else if (exception != null) {
                        result.setFailure(exception);
                        metricsRecorder.timeoutFailed(end - start);
                    } else {
                        metricsRecorder.timeoutSucceeded(end - start);
                    }
                });
        result.waitForFutureInitialization();
        return result;
    }
}