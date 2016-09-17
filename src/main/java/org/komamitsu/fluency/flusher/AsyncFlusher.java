package org.komamitsu.fluency.flusher;

import org.komamitsu.fluency.buffer.Buffer;
import org.komamitsu.fluency.sender.Sender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AsyncFlusher
        extends Flusher
{
    private static final Logger LOG = LoggerFactory.getLogger(AsyncFlusher.class);
    private final BlockingQueue<Boolean> eventQueue = new LinkedBlockingQueue<Boolean>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Config config;
    private final Runnable task = new Runnable() {
            @Override
            public void run()
            {
                Boolean wakeup = null;
                while (!executorService.isShutdown()) {
                    try {
                        wakeup = eventQueue.poll(AsyncFlusher.this.config.getFlushIntervalMillis(), TimeUnit.MILLISECONDS);
                        boolean force = wakeup != null;
                        buffer.flush(sender, force);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    catch (IOException e) {
                        LOG.error("Failed to flush", e);
                    }
                }

                if (wakeup == null) {
                    // The above run loop can quit without force buffer flush in the following cases
                    // - close() is called right after the constructor is called.
                    // - close() is called right after the repeated non-force buffer flush executed in the run loop
                    //
                    // In these cases, remaining buffers wont't be flushed.
                    // So force buffer flush is executed here just in case
                    try {
                        buffer.flush(sender, true);
                    }
                    catch (IOException e) {
                        LOG.error("Failed to flush", e);
                    }
                }

                closeBuffer();
            }
        };

    private AsyncFlusher(final Buffer buffer, final Sender sender, final Config config)
    {
        super(buffer, sender, config.getBaseConfig());
        this.config = config;
        executorService.execute(task);
    }

    @Override
    protected void flushInternal(boolean force)
            throws IOException
    {
        if (force) {
            try {
                eventQueue.put(true);
            }
            catch (InterruptedException e) {
                LOG.warn("Failed to force flushing buffer", e);
            }
        }
    }

    @Override
    protected void closeInternal()
            throws IOException
    {
        try {
            eventQueue.put(true);
        }
        catch (InterruptedException e) {
            LOG.warn("Failed to close buffer", e);
        }
        executorService.shutdown();
        try {
            executorService.awaitTermination(this.config.getWaitAfterClose(), TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            LOG.warn("1st awaitTermination was interrupted", e);
            Thread.currentThread().interrupt();
        }

        if (!executorService.isTerminated()) {
            executorService.shutdownNow();
        }
    }

    @Override
    public boolean isTerminated()
    {
        return executorService.isTerminated();
    }

    @Override
    public String toString()
    {
        return "AsyncFlusher{" +
                "eventQueue=" + eventQueue +
                ", config=" + config +
                ", task=" + task +
                "} " + super.toString();
    }

    public static class Config
        implements Flusher.Instantiator
    {
        private final Flusher.Config baseConfig = new Flusher.Config();

        public Flusher.Config getBaseConfig()
        {
            return baseConfig;
        }

        public int getFlushIntervalMillis()
        {
            return baseConfig.getFlushIntervalMillis();
        }

        public int getWaitAfterClose()
        {
            return baseConfig.getWaitAfterClose();
        }

        public Config setWaitAfterClose(int waitAfterClose)
        {
            baseConfig.setWaitAfterClose(waitAfterClose);
            return this;
        }

        public Config setFlushIntervalMillis(int flushIntervalMillis)
        {
            baseConfig.setFlushIntervalMillis(flushIntervalMillis);
            return this;
        }

        @Override
        public AsyncFlusher createInstance(Buffer buffer, Sender sender)
        {
            return new AsyncFlusher(buffer, sender, this);
        }
    }
}
