package org.databasebrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.application.sql.SqlService;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.CoreException;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Sql;
import org.junit.jupiter.api.Test;

class BrowserLifecycleTest {
    private static final Settings SETTINGS =
        new Settings(CoreUseCasesTest.ADAPTER, "review", Map.of(), Map.of());

    @Test
    void runsAllCleanupHandlersOnceEvenIfOneFails() {
        var adapter = new CoreUseCasesTest.Fake();
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            List<SessionId> cleaned = new ArrayList<>();
            sessions.onSessionClosed(id -> {
                assertFalse(adapter.live.contains(id));
                throw new CoreException(Code.INTERNAL, "Cleanup failed");
            });
            sessions.onSessionClosed(cleaned::add);

            SessionId id = sessions.connect(SETTINGS).id();
            assertCode(Code.INTERNAL, () -> sessions.disconnect(id));
            sessions.disconnect(id);

            assertEquals(List.of(id), cleaned);
            assertTrue(sessions.list().isEmpty());
        }
    }

    @Test
    void sqlServiceUsesRegistrySessionLifecycle() {
        var adapter = new CoreUseCasesTest.Fake();
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var sql = new SqlService(sessions);
            SessionId id = sessions.connect(SETTINGS).id();

            var result = sql.execute(id, request(sql)).result().orElseThrow();
            assertTrue(sql.fetchRows(id, result, 1).complete());

            sessions.disconnect(id);
            assertCode(Code.UNKNOWN_SESSION, () -> sql.fetchRows(id, result, 1));
            assertTrue(adapter.cursors.isEmpty());
        }
    }

    @Test
    void reservesCancellationBeforeCallingExecute() {
        CoreUseCasesTest.Fake adapter = new CoreUseCasesTest.Fake();
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var sql = new SqlService(sessions);
            SessionId session = sessions.connect(SETTINGS).id();
            Sql.Request request = request(sql);

            assertEquals(Sql.CancelResult.REQUESTED, sql.cancel(session, request.operationId()));
            assertCode(Code.CANCELLED, () -> sql.execute(session, request));
            assertEquals(Sql.CancelResult.NOT_ACTIVE, sql.cancel(session, request.operationId()));
            assertTrue(adapter.cursors.isEmpty());
        }
    }

    @Test
    void cancelsWhileLookingUpCapabilities() throws Exception {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CoreUseCasesTest.Fake adapter = new CoreUseCasesTest.Fake() {
            @Override
            public Capabilities getCapabilities(SessionId session) {
                checking.countDown();
                await(resume);
                return super.getCapabilities(session);
            }
        };
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var sql = new SqlService(sessions);
            try {
                SessionId session = sessions.connect(SETTINGS).id();
                Sql.Request request = request(sql);
                var execution = workers.submit(() -> sql.execute(session, request));
                await(checking);
                assertEquals(Sql.CancelResult.REQUESTED, sql.cancel(session, request.operationId()));
                resume.countDown();
                assertFutureCode(Code.CANCELLED, execution);
                assertTrue(adapter.cursors.isEmpty());
            } finally {
                resume.countDown();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void cancelsEachFetchByItsAdapterOperationAndCancelsOnDisconnect() throws Exception {
        var reads = new LinkedBlockingQueue<OperationId>();
        var cancellations = new LinkedBlockingQueue<OperationId>();
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondCancelled = new CountDownLatch(1);
        CoreUseCasesTest.Fake adapter = new CoreUseCasesTest.Fake() {
            @Override
            public Sql.Batch fetchRows(SessionId session, ResultId result, OperationId operation,
                int size) {
                CountDownLatch cancelled = firstCancelled.getCount() > 0
                    ? firstCancelled : secondCancelled;
                reads.add(operation);
                await(cancelled);
                throw new CoreException(Code.CANCELLED, "Read cancelled");
            }

            @Override
            public Sql.CancelResult cancel(SessionId session, OperationId operation) {
                cancellations.add(operation);
                if (firstCancelled.getCount() > 0) {
                    firstCancelled.countDown();
                } else {
                    secondCancelled.countDown();
                }
                return Sql.CancelResult.REQUESTED;
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var sql = new SqlService(sessions);
            try {
                SessionId session = sessions.connect(SETTINGS).id();
                Sql.Request request = request(sql);
                ResultId result = sql.execute(session, request).result().orElseThrow();
                var first = workers.submit(() -> sql.fetchRows(session, result, 1));
                OperationId firstRead = reads.poll(5, TimeUnit.SECONDS);

                assertNotEquals(request.operationId(), firstRead);
                assertEquals(Sql.CancelResult.REQUESTED, sql.cancel(session, request.operationId()));
                assertEquals(firstRead, cancellations.poll(5, TimeUnit.SECONDS));
                assertFutureCode(Code.CANCELLED, first);
                assertEquals(Sql.CancelResult.NOT_ACTIVE, sql.cancel(session, request.operationId()));

                var second = workers.submit(() -> sql.fetchRows(session, result, 1));
                OperationId secondRead = reads.poll(5, TimeUnit.SECONDS);
                assertNotEquals(firstRead, secondRead);
                var disconnect = workers.submit(() -> sessions.disconnect(session));
                assertEquals(secondRead, cancellations.poll(5, TimeUnit.SECONDS));
                assertFutureCode(Code.CANCELLED, second);
                disconnect.get(5, TimeUnit.SECONDS);
                assertTrue(adapter.cursors.isEmpty());
            } finally {
                firstCancelled.countDown();
                secondCancelled.countDown();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void shutdownTracksLateConnectionsAndRetriesFailedCleanup() throws Exception {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<SessionId> lateSession = new AtomicReference<>();
        AtomicBoolean firstCleanup = new AtomicBoolean(true);
        CoreUseCasesTest.Fake adapter = new CoreUseCasesTest.Fake() {
            @Override
            public Opened connect(Settings settings) {
                Opened opened = super.connect(settings);
                if (settings.displayName().equals("late")) {
                    lateSession.set(opened.id());
                    opening.countDown();
                    await(resume);
                }
                return opened;
            }

            @Override
            public void disconnect(SessionId session) {
                if (session.equals(lateSession.get()) && firstCleanup.getAndSet(false)) {
                    throw new CoreException(Code.CONNECTION, "Temporary close failure");
                }
                super.disconnect(session);
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            try {
                SessionId established = sessions.connect(SETTINGS).id();
                Settings late = new Settings(SETTINGS.adapter(), "late", Map.of(), Map.of());
                var connecting = workers.submit(() -> sessions.connect(late));
                await(opening);
                var shutdown = workers.submit(sessions::close);
                awaitShutdown(sessions, established);
                assertFalse(shutdown.isDone());
                resume.countDown();

                assertFutureCode(Code.CONFLICT, connecting);
                assertFutureCode(Code.CONNECTION, shutdown);
                assertEquals(Set.of(lateSession.get()), adapter.live);
                sessions.close();
                assertTrue(adapter.live.isEmpty());
            } finally {
                resume.countDown();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void shutdownCancelsActiveWorkBeforeWaitingForLateOpen() throws Exception {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch sqlCancelled = new CountDownLatch(1);
        CoreUseCasesTest.Fake adapter = new CoreUseCasesTest.Fake() {
            @Override
            public Opened connect(Settings settings) {
                if (settings.displayName().equals("late")) {
                    opening.countDown();
                    await(sqlCancelled);
                }
                return super.connect(settings);
            }

            @Override
            public Sql.Execution execute(SessionId session, Sql.Request request) {
                executing.countDown();
                await(sqlCancelled);
                throw new CoreException(Code.CANCELLED, "SQL cancelled");
            }

            @Override
            public Sql.CancelResult cancel(SessionId session, OperationId operation) {
                sqlCancelled.countDown();
                return Sql.CancelResult.REQUESTED;
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var sql = new SqlService(sessions);
            try {
                SessionId established = sessions.connect(SETTINGS).id();
                var execution = workers.submit(() -> sql.execute(established, request(sql)));
                await(executing);
                Settings late = new Settings(SETTINGS.adapter(), "late", Map.of(), Map.of());
                var connecting = workers.submit(() -> sessions.connect(late));
                await(opening);
                var shutdown = workers.submit(sessions::close);

                shutdown.get(5, TimeUnit.SECONDS);
                assertFutureCode(Code.CANCELLED, execution);
                assertFutureCode(Code.CONFLICT, connecting);
                assertTrue(adapter.live.isEmpty());
            } finally {
                sqlCancelled.countDown();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void canRetryFailedCursorCloseThroughSourceOrResult() {
        AtomicBoolean failClose = new AtomicBoolean();
        CoreUseCasesTest.Fake adapter = new CoreUseCasesTest.Fake() {
            @Override
            public void closeResult(SessionId session, ResultId result) {
                if (failClose.getAndSet(false)) {
                    throw new CoreException(Code.CONNECTION, "Temporary close failure");
                }
                super.closeResult(session, result);
            }
        };
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var sql = new SqlService(sessions);
            SessionId session = sessions.connect(SETTINGS).id();
            ResultId first = sql.execute(session, request(sql)).result().orElseThrow();
            var firstSource = sql.remainingRows(session, first);
            failClose.set(true);
            assertCode(Code.CONNECTION, firstSource::close);
            assertCode(Code.NOT_FOUND, () -> firstSource.fetch(1));
            assertTrue(adapter.cursors.containsKey(first));
            firstSource.close();

            ResultId second = sql.execute(session, request(sql)).result().orElseThrow();
            var secondSource = sql.remainingRows(session, second);
            failClose.set(true);
            assertCode(Code.CONNECTION, secondSource::close);
            sql.closeResult(session, second);
            secondSource.close();
            assertTrue(adapter.cursors.isEmpty());

            ResultId third = sql.execute(session, request(sql)).result().orElseThrow();
            var thirdSource = sql.remainingRows(session, third);
            sessions.disconnect(session);
            thirdSource.close();
        }
    }

    private static Sql.Request request(SqlService sql) {
        return new Sql.Request(sql.newOperationId(), "SELECT 1", List.of(),
            new Sql.Options(ExecutionMode.SINGLE_STATEMENT, TransactionPolicy.AUTOCOMMIT, 1,
                Duration.ofSeconds(5)));
    }

    private static void assertCode(Code code, Runnable action) {
        assertEquals(code, assertThrows(CoreException.class, action::run).code());
    }

    private static void assertFutureCode(Code code, Future<?> result) {
        var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        assertEquals(code, ((CoreException) error.getCause()).code());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test worker");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitShutdown(SessionRegistry sessions, SessionId established)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                sessions.checkConnection(established);
            } catch (CoreException e) {
                assertEquals(Code.UNKNOWN_SESSION, e.code());
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Shutdown did not block new work");
    }
}
