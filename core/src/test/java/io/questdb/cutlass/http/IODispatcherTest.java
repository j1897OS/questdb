/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.http;

import io.questdb.cairo.*;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cutlass.NetUtils;
import io.questdb.cutlass.http.processors.JsonQueryProcessor;
import io.questdb.cutlass.http.processors.StaticContentProcessor;
import io.questdb.cutlass.http.processors.StaticContentProcessorConfiguration;
import io.questdb.cutlass.http.processors.TextImportProcessor;
import io.questdb.cutlass.json.JsonException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.*;
import io.questdb.network.*;
import io.questdb.std.*;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import io.questdb.std.time.MillisecondClock;
import io.questdb.test.tools.TestUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class IODispatcherTest {
    private static final Log LOG = LogFactory.getLog(IODispatcherTest.class);
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static void assertDownloadResponse(long fd, Rnd rnd, long buffer, int len, int nonRepeatedContentLength, String expectedResponseHeader, long expectedResponseLen) {
        int expectedHeaderLen = expectedResponseHeader.length();
        int headerCheckRemaining = expectedResponseHeader.length();
        long downloadedSoFar = 0;
        int contentRemaining = 0;
        while (downloadedSoFar < expectedResponseLen) {
            int contentOffset = 0;
            int n = Net.recv(fd, buffer, len);
            Assert.assertTrue(n > -1);
            if (n > 0) {
                if (headerCheckRemaining > 0) {
                    for (int i = 0; i < n && headerCheckRemaining > 0; i++) {
                        if (expectedResponseHeader.charAt(expectedHeaderLen - headerCheckRemaining) != (char) Unsafe.getUnsafe().getByte(buffer + i)) {
                            Assert.fail("at " + (expectedHeaderLen - headerCheckRemaining));
                        }
                        headerCheckRemaining--;
                        contentOffset++;
                    }
                }

                if (headerCheckRemaining == 0) {
                    for (int i = contentOffset; i < n; i++) {
                        if (contentRemaining == 0) {
                            contentRemaining = nonRepeatedContentLength;
                            rnd.reset();
                        }
                        Assert.assertEquals(rnd.nextByte(), Unsafe.getUnsafe().getByte(buffer + i));
                        contentRemaining--;
                    }

                }
                downloadedSoFar += n;
            }
        }
    }

    private static void sendRequest(String request, long fd, long buffer) {
        final int requestLen = request.length();
        Chars.strcpy(request, requestLen, buffer);
        Assert.assertEquals(requestLen, Net.send(fd, buffer, requestLen));
    }

    @Before
    public void setUp() throws Exception {
        temp.create();
    }

    @Test
    public void testBiasWrite() throws Exception {

        LOG.info().$("started testBiasWrite").$();

        TestUtils.assertMemoryLeak(() -> {

            SOCountDownLatch connectLatch = new SOCountDownLatch(1);
            SOCountDownLatch contextClosedLatch = new SOCountDownLatch(1);

            try (IODispatcher<HelloContext> dispatcher = IODispatchers.create(
                    new DefaultIODispatcherConfiguration() {
                        @Override
                        public int getInitialBias() {
                            return IODispatcherConfiguration.BIAS_WRITE;
                        }
                    },
                    fd -> {
                        connectLatch.countDown();
                        return new HelloContext(fd, contextClosedLatch);
                    }
            )) {
                AtomicBoolean serverRunning = new AtomicBoolean(true);
                SOCountDownLatch serverHaltLatch = new SOCountDownLatch(1);

                new Thread(() -> {
                    while (serverRunning.get()) {
                        dispatcher.run();
                        dispatcher.processIOQueue(
                                (operation, context, disp) -> {
                                    if (operation == IOOperation.WRITE) {
                                        Assert.assertEquals(1024, Net.send(context.getFd(), context.buffer, 1024));
                                        disp.disconnect(context);
                                    }
                                }
                        );
                    }
                    serverHaltLatch.countDown();
                }).start();


                long fd = Net.socketTcp(true);
                try {
                    long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                    try {
                        Assert.assertTrue(fd > -1);
                        Assert.assertEquals(0, Net.connect(fd, sockAddr));

                        connectLatch.await();

                        long buffer = Unsafe.malloc(2048);
                        try {
                            Assert.assertEquals(1024, Net.recv(fd, buffer, 1024));
                        } finally {
                            Unsafe.free(buffer, 2048);
                        }


                        Assert.assertEquals(0, Net.close(fd));
                        LOG.info().$("closed [fd=").$(fd).$(']').$();

                        contextClosedLatch.await();

                        serverRunning.set(false);
                        serverHaltLatch.await();

                        Assert.assertEquals(0, dispatcher.getConnectionCount());
                    } finally {
                        Net.freeSockAddr(sockAddr);
                    }
                } finally {
                    Net.close(fd);
                }
            }
        });
    }

    @Test
    public void testConnectDisconnect() throws Exception {

        LOG.info().$("started testConnectDisconnect").$();

        TestUtils.assertMemoryLeak(() -> {
            HttpServerConfiguration httpServerConfiguration = new DefaultHttpServerConfiguration();

            SOCountDownLatch connectLatch = new SOCountDownLatch(1);
            SOCountDownLatch contextClosedLatch = new SOCountDownLatch(1);
            AtomicInteger closeCount = new AtomicInteger(0);

            try (IODispatcher<HttpConnectionContext> dispatcher = IODispatchers.create(
                    new DefaultIODispatcherConfiguration(),
                    new IOContextFactory<HttpConnectionContext>() {
                        @Override
                        public HttpConnectionContext newInstance(long fd) {
                            connectLatch.countDown();
                            return new HttpConnectionContext(httpServerConfiguration) {
                                @Override
                                public void close() {
                                    // it is possible that context is closed twice in error
                                    // when crashes occur put debug line here to see how many times
                                    // context is closed
                                    if (closeCount.incrementAndGet() == 1) {
                                        super.close();
                                        contextClosedLatch.countDown();
                                    }
                                }
                            }.of(fd);
                        }
                    }
            )) {
                HttpRequestProcessorSelector selector = new HttpRequestProcessorSelector() {

                    @Override
                    public HttpRequestProcessor select(CharSequence url) {
                        return null;
                    }

                    @Override
                    public HttpRequestProcessor getDefaultProcessor() {
                        return new HttpRequestProcessor() {
                            @Override
                            public void onHeadersReady(HttpConnectionContext connectionContext) {
                            }

                            @Override
                            public void onRequestComplete(HttpConnectionContext connectionContext, IODispatcher<HttpConnectionContext> dispatcher1) {
                            }
                        };
                    }

                    @Override
                    public void close() {
                    }
                };

                AtomicBoolean serverRunning = new AtomicBoolean(true);
                SOCountDownLatch serverHaltLatch = new SOCountDownLatch(1);

                new Thread(() -> {

                    while (serverRunning.get()) {
                        dispatcher.run();
                        dispatcher.processIOQueue(
                                (operation, context, disp) -> context.handleClientOperation(operation, disp, selector)
                        );
                    }
                    serverHaltLatch.countDown();
                }).start();


                long fd = Net.socketTcp(true);
                try {
                    long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                    try {
                        Assert.assertTrue(fd > -1);
                        Assert.assertEquals(0, Net.connect(fd, sockAddr));

                        connectLatch.await();

                        Assert.assertEquals(0, Net.close(fd));
                        LOG.info().$("closed [fd=").$(fd).$(']').$();

                        contextClosedLatch.await();

                        serverRunning.set(false);
                        serverHaltLatch.await();

                        Assert.assertEquals(0, dispatcher.getConnectionCount());
                    } finally {
                        Net.freeSockAddr(sockAddr);
                    }
                } finally {
                    Net.close(fd);
                }

                Assert.assertEquals(1, closeCount.get());
            }
        });
    }

    @Test
    public void testHttpLong256AndCharImport() throws JsonException {
        // this script uploads text file:
        // 0x5c504ed432cb51138bcf09aa5e8a410dd4a1e204ef84bfed1be16dfba1b22060,a
        // 0x19f1df2c7ee6b464720ad28e903aeda1a5ad8780afc22f0b960827bd4fcf656d,b
        // 0x9e6e19637bb625a8ff3d052b7c2fe57dc78c55a15d258d77c43d5a9c160b0384,p
        // 0xcb9378977089c773c074045b20ede2cdcc3a6ff562f4e64b51b20c5205234525,w
        // 0xd23ae9b2e5c68caf2c5663af5ba27679dc3b3cb781c4dc698abbd17d63e32e9f,t

        final String uploadScript = ">504f5354202f696d703f666d743d6a736f6e266f76657277726974653d7472756520485454502f312e310d0a486f73743a206c6f63616c686f73743a393030300d0a436f6e6e656374696f6e3a206b6565702d616c6976650d0a436f6e74656e742d4c656e6774683a203534380d0a4163636570743a202a2f2a0d0a582d5265717565737465642d576974683a20584d4c48747470526571756573740d0a557365722d4167656e743a204d6f7a696c6c612f352e30202857696e646f7773204e542031302e303b2057696e36343b2078363429204170706c655765624b69742f3533372e333620284b48544d4c2c206c696b65204765636b6f29204368726f6d652f37362e302e333830392e313030205361666172692f3533372e33360d0a5365632d46657463682d4d6f64653a20636f72730d0a436f6e74656e742d547970653a206d756c7469706172742f666f726d2d646174613b20626f756e646172793d2d2d2d2d5765624b6974466f726d426f756e64617279386c75374239696e37567a5767614a4f0d0a4f726967696e3a20687474703a2f2f6c6f63616c686f73743a393030300d0a5365632d46657463682d536974653a2073616d652d6f726967696e0d0a526566657265723a20687474703a2f2f6c6f63616c686f73743a393030302f696e6465782e68746d6c0d0a4163636570742d456e636f64696e673a20677a69702c206465666c6174652c2062720d0a4163636570742d4c616e67756167653a20656e2d47422c656e2d55533b713d302e392c656e3b713d302e380d0a0d0a\n" +
                ">2d2d2d2d2d2d5765624b6974466f726d426f756e64617279386c75374239696e37567a5767614a4f0d0a436f6e74656e742d446973706f736974696f6e3a20666f726d2d646174613b206e616d653d2264617461223b2066696c656e616d653d2273616d706c652e637376220d0a436f6e74656e742d547970653a206170706c69636174696f6e2f766e642e6d732d657863656c0d0a0d0a3078356335303465643433326362353131333862636630396161356538613431306464346131653230346566383462666564316265313664666261316232323036302c610d0a3078313966316466326337656536623436343732306164323865393033616564613161356164383738306166633232663062393630383237626434666366363536642c620d0a3078396536653139363337626236323561386666336430353262376332666535376463373863353561313564323538643737633433643561396331363062303338342c700d0a3078636239333738393737303839633737336330373430343562323065646532636463633361366666353632663465363462353162323063353230353233343532352c770d0a3078643233616539623265356336386361663263353636336166356261323736373964633362336362373831633464633639386162626431376436336533326539662c740d0a0d0a2d2d2d2d2d2d5765624b6974466f726d426f756e64617279386c75374239696e37567a5767614a4f2d2d0d0a\n" +
                "<485454502f312e3120323030204f4b0d0a5365727665723a20717565737444422f312e300d0a446174653a205468752c2031204a616e20313937302030303a30303a303020474d540d0a5472616e736665722d456e636f64696e673a206368756e6b65640d0a436f6e74656e742d547970653a206170706c69636174696f6e2f6a736f6e3b20636861727365743d7574662d380d0a\n" +
                "<0d0a63380d0a\n" +
                "<7b22737461747573223a224f4b222c226c6f636174696f6e223a2273616d706c652e637376222c22726f777352656a6563746564223a302c22726f7773496d706f72746564223a352c22686561646572223a66616c73652c22636f6c756d6e73223a5b7b226e616d65223a226630222c2274797065223a224c4f4e47323536222c2273697a65223a33322c226572726f7273223a307d2c7b226e616d65223a226631222c2274797065223a2243484152222c2273697a65223a322c226572726f7273223a307d5d7d\n" +
                "<0d0a30300d0a\n" +
                "<0d0a\n";

        final String expectedTableMetadata = "{\"columnCount\":2,\"columns\":[{\"index\":0,\"name\":\"f0\",\"type\":\"LONG256\"},{\"index\":1,\"name\":\"f1\",\"type\":\"CHAR\"}],\"timestampIndex\":-1}";

        final String baseDir = temp.getRoot().getAbsolutePath();

        try (
                CairoEngine cairoEngine = new CairoEngine(new DefaultCairoConfiguration(baseDir));
                HttpServer ignored = HttpServer.create(
                        new DefaultHttpServerConfiguration() {
                            @Override
                            public MillisecondClock getClock() {
                                return StationaryMillisClock.INSTANCE;
                            }
                        },
                        null,
                        LOG,
                        cairoEngine
                )) {

            // upload file
            NetUtils.playScript(NetworkFacadeImpl.INSTANCE, uploadScript, "127.0.0.1", 9001);

            try (TableReader reader = cairoEngine.getReader(AllowAllCairoSecurityContext.INSTANCE, "sample.csv", TableUtils.ANY_TABLE_VERSION)) {
                StringSink sink = new StringSink();
                reader.getMetadata().toJson(sink);
                TestUtils.assertEquals(expectedTableMetadata, sink);
            }

            final String selectAsJsonScript = ">474554202f657865633f71756572793d25304125304125323773616d706c652e637376253237266c696d69743d302532433130303026636f756e743d7472756520485454502f312e310d0a486f73743a206c6f63616c686f73743a393030300d0a436f6e6e656374696f6e3a206b6565702d616c6976650d0a4163636570743a202a2f2a0d0a582d5265717565737465642d576974683a20584d4c48747470526571756573740d0a557365722d4167656e743a204d6f7a696c6c612f352e30202857696e646f7773204e542031302e303b2057696e36343b2078363429204170706c655765624b69742f3533372e333620284b48544d4c2c206c696b65204765636b6f29204368726f6d652f37362e302e333830392e313030205361666172692f3533372e33360d0a5365632d46657463682d4d6f64653a20636f72730d0a5365632d46657463682d536974653a2073616d652d6f726967696e0d0a526566657265723a20687474703a2f2f6c6f63616c686f73743a393030302f696e6465782e68746d6c0d0a4163636570742d456e636f64696e673a20677a69702c206465666c6174652c2062720d0a4163636570742d4c616e67756167653a20656e2d47422c656e2d55533b713d302e392c656e3b713d302e380d0a0d0a\n" +
                    "<485454502f312e3120323030204f4b0d0a5365727665723a20717565737444422f312e300d0a446174653a205468752c2031204a616e20313937302030303a30303a303020474d540d0a5472616e736665722d456e636f64696e673a206368756e6b65640d0a436f6e74656e742d547970653a206170706c69636174696f6e2f6a736f6e3b20636861727365743d7574662d380d0a4b6565702d416c6976653a2074696d656f75743d352c206d61783d31303030300d0a\n" +
                    "<0d0a303166300d0a\n" +
                    "<7b227175657279223a225c6e5c6e2773616d706c652e63737627222c22636f6c756d6e73223a5b7b226e616d65223a226630222c2274797065223a224c4f4e47323536227d2c7b226e616d65223a226631222c2274797065223a2243484152227d5d2c2264617461736574223a5b5b22307835633530346564343332636235313133386263663039616135653861343130646434613165323034656638346266656431626531366466626131623232303630222c2261225d2c5b22307831396631646632633765653662343634373230616432386539303361656461316135616438373830616663323266306239363038323762643466636636353664222c2262225d2c5b22307839653665313936333762623632356138666633643035326237633266653537646337386335356131356432353864373763343364356139633136306230333834222c2270225d2c5b22307863623933373839373730383963373733633037343034356232306564653263646363336136666635363266346536346235316232306335323035323334353235222c2277225d2c5b22307864323361653962326535633638636166326335363633616635626132373637396463336233636237383163346463363938616262643137643633653332653966222c2274225d5d2c22636f756e74223a357d\n" +
                    "<0d0a30300d0a\n" +
                    "<0d0a";

            // select * from 'sample.csv'
            NetUtils.playScript(NetworkFacadeImpl.INSTANCE, selectAsJsonScript, "127.0.0.1", 9001);

            final String downloadAsCsvScript = ">474554202f6578703f71756572793d25304125304125323773616d706c652e63737625323720485454502f312e310d0a486f73743a206c6f63616c686f73743a393030300d0a436f6e6e656374696f6e3a206b6565702d616c6976650d0a557067726164652d496e7365637572652d52657175657374733a20310d0a557365722d4167656e743a204d6f7a696c6c612f352e30202857696e646f7773204e542031302e303b2057696e36343b2078363429204170706c655765624b69742f3533372e333620284b48544d4c2c206c696b65204765636b6f29204368726f6d652f37362e302e333830392e313030205361666172692f3533372e33360d0a5365632d46657463682d4d6f64653a206e617669676174650d0a4163636570743a20746578742f68746d6c2c6170706c69636174696f6e2f7868746d6c2b786d6c2c6170706c69636174696f6e2f786d6c3b713d302e392c696d6167652f776562702c696d6167652f61706e672c2a2f2a3b713d302e382c6170706c69636174696f6e2f7369676e65642d65786368616e67653b763d62330d0a5365632d46657463682d536974653a2073616d652d6f726967696e0d0a526566657265723a20687474703a2f2f6c6f63616c686f73743a393030302f696e6465782e68746d6c0d0a4163636570742d456e636f64696e673a20677a69702c206465666c6174652c2062720d0a4163636570742d4c616e67756167653a20656e2d47422c656e2d55533b713d302e392c656e3b713d302e380d0a0d0a\n" +
                    "<485454502f312e3120323030204f4b0d0a5365727665723a20717565737444422f312e300d0a446174653a205468752c2031204a616e20313937302030303a30303a303020474d540d0a5472616e736665722d456e636f64696e673a206368756e6b65640d0a436f6e74656e742d547970653a20746578742f6373763b20636861727365743d7574662d380d0a436f6e74656e742d446973706f736974696f6e3a206174746163686d656e743b2066696c656e616d653d22717565737464622d71756572792d302e637376220d0a4b6565702d416c6976653a2074696d656f75743d352c206d61783d31303030300d0a\n" +
                    "<0d0a303136390d0a\n" +
                    "<226630222c226631220d0a3078356335303465643433326362353131333862636630396161356538613431306464346131653230346566383462666564316265313664666261316232323036302c610d0a3078313966316466326337656536623436343732306164323865393033616564613161356164383738306166633232663062393630383237626434666366363536642c620d0a3078396536653139363337626236323561386666336430353262376332666535376463373863353561313564323538643737633433643561396331363062303338342c700d0a3078636239333738393737303839633737336330373430343562323065646532636463633361366666353632663465363462353162323063353230353233343532352c770d0a3078643233616539623265356336386361663263353636336166356261323736373964633362336362373831633464633639386162626431376436336533326539662c740d0a\n" +
                    "<0d0a30300d0a\n" +
                    "<0d0a";

            // download select * from 'sample.csv' as csv
            NetUtils.playScript(NetworkFacadeImpl.INSTANCE, downloadAsCsvScript, "127.0.0.1", 9001);
        }
    }

    @Test
    public void testImportMultipleOnSameConnection() throws Exception {
        testImport(
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "\r\n" +
                        "05d7\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|      Location:  |                          fhv_tripdata_2017-02.csv  |        Pattern  | Locale  |    Errors  |\r\n" +
                        "|   Partition by  |                                              NONE  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|   Rows handled  |                                                24  |                 |         |            |\r\n" +
                        "|  Rows imported  |                                                24  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|              0  |                                DispatchingBaseNum  |                   STRING  |         0  |\r\n" +
                        "|              1  |                                    PickupDateTime  |                     DATE  |         0  |\r\n" +
                        "|              2  |                                   DropOffDatetime  |                   STRING  |         0  |\r\n" +
                        "|              3  |                                      PUlocationID  |                   STRING  |         0  |\r\n" +
                        "|              4  |                                      DOlocationID  |                   STRING  |         0  |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "\r\n" +
                        "00\r\n" +
                        "\r\n"
                ,
                "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "User-Agent: curl/7.64.0\r\n" +
                        "Accept: */*\r\n" +
                        "Content-Length: 437760673\r\n" +
                        "Content-Type: multipart/form-data; boundary=------------------------27d997ca93d2689d\r\n" +
                        "Expect: 100-continue\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"schema\"; filename=\"schema.json\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "[\r\n" +
                        "  {\r\n" +
                        "    \"name\": \"date\",\r\n" +
                        "    \"type\": \"DATE\",\r\n" +
                        "    \"pattern\": \"d MMMM y.\",\r\n" +
                        "    \"locale\": \"ru-RU\"\r\n" +
                        "  }\r\n" +
                        "]\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"data\"; filename=\"fhv_tripdata_2017-02.csv\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "Dispatching_base_num,Pickup_DateTime,DropOff_datetime,PUlocationID,DOlocationID\r\n" +
                        "B00008,2017-02-01 00:30:00,,,\r\n" +
                        "B00008,2017-02-01 00:40:00,,,\r\n" +
                        "B00009,2017-02-01 00:30:00,,,\r\n" +
                        "B00013,2017-02-01 00:11:00,,,\r\n" +
                        "B00013,2017-02-01 00:41:00,,,\r\n" +
                        "B00013,2017-02-01 00:00:00,,,\r\n" +
                        "B00013,2017-02-01 00:53:00,,,\r\n" +
                        "B00013,2017-02-01 00:44:00,,,\r\n" +
                        "B00013,2017-02-01 00:05:00,,,\r\n" +
                        "B00013,2017-02-01 00:54:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:46:00,,,\r\n" +
                        "B00014,2017-02-01 00:54:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:26:00,,,\r\n" +
                        "B00014,2017-02-01 00:55:00,,,\r\n" +
                        "B00014,2017-02-01 00:47:00,,,\r\n" +
                        "B00014,2017-02-01 00:05:00,,,\r\n" +
                        "B00014,2017-02-01 00:58:00,,,\r\n" +
                        "B00014,2017-02-01 00:33:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d--"
                , NetworkFacadeImpl.INSTANCE
                , false
                , 150
        );
    }

    public void testImport(
            String response,
            String request,
            NetworkFacade nf,
            boolean expectDisconnect,
            int requestCount
    ) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            final String baseDir = temp.getRoot().getAbsolutePath();
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(baseDir,
                    false,
                    false
            );
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return new int[]{-1, -1};
                }

                @Override
                public int getWorkerCount() {
                    return 2;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });

            try (CairoEngine engine = new CairoEngine(new DefaultCairoConfiguration(baseDir));
                 HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return "/upload";
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new TextImportProcessor(
                                httpConfiguration.getTextImportProcessorConfiguration(),
                                engine
                        );
                    }
                });

                workerPool.start(LOG);

                long fd = Net.socketTcp(true);
                try {
                    sendAndReceive(
                            nf,
                            request,
                            response,
                            requestCount,
                            0,
                            false,
                            expectDisconnect);
                } finally {
                    Net.close(fd);
                    workerPool.halt();
                }
            }
        });
    }

    @Test
    public void testImportEmptyData() throws Exception {
        testImport(
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "\r\n" +
                        "0398\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|      Location:  |                          fhv_tripdata_2017-02.csv  |        Pattern  | Locale  |    Errors  |\r\n" +
                        "|   Partition by  |                                              NONE  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|   Rows handled  |                                                 0  |                 |         |            |\r\n" +
                        "|  Rows imported  |                                                 0  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "\r\n" +
                        "00\r\n" +
                        "\r\n",
                "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "User-Agent: curl/7.64.0\r\n" +
                        "Accept: */*\r\n" +
                        "Content-Length: 437760673\r\n" +
                        "Content-Type: multipart/form-data; boundary=------------------------27d997ca93d2689d\r\n" +
                        "Expect: 100-continue\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"data\"; filename=\"fhv_tripdata_2017-02.csv\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d--",
                NetworkFacadeImpl.INSTANCE,
                false,
                120
        );
    }

    @Test
    public void testImportForceUnknownDate() throws Exception {
        testImport(
                "HTTP/1.1 400 Bad request\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "\r\n" +
                        "2c\r\n" +
                        "{\"status\":\"DATE format pattern is required\"}\r\n" +
                        "00\r\n" +
                        "\r\n",
                "POST /upload?fmt=json&overwrite=true&forceHeader=true&name=clipboard-157200856 HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Content-Length: 832\r\n" +
                        "Accept: */*\r\n" +
                        "Origin: http://localhost:9000\r\n" +
                        "X-Requested-With: XMLHttpRequest\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/77.0.3865.120 Safari/537.36\r\n" +
                        "Sec-Fetch-Mode: cors\r\n" +
                        "Content-Type: multipart/form-data; boundary=----WebKitFormBoundaryOsOAD9cPKyHuxyBV\r\n" +
                        "Sec-Fetch-Site: same-origin\r\n" +
                        "Referer: http://localhost:9000/index.html\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n" +
                        "------WebKitFormBoundaryOsOAD9cPKyHuxyBV\r\n" +
                        "Content-Disposition: form-data; name=\"schema\"\r\n" +
                        "\r\n" +
                        "[{\"name\":\"timestamp\",\"type\":\"DATE\"},{\"name\":\"bid\",\"type\":\"INT\"}]\r\n" +
                        "------WebKitFormBoundaryOsOAD9cPKyHuxyBV\r\n" +
                        "Content-Disposition: form-data; name=\"data\"\r\n" +
                        "\r\n" +
                        "timestamp,bid\r\n" +
                        "27/05/2018 00:00:01,100\r\n" +
                        "27/05/2018 00:00:02,101\r\n" +
                        "27/05/2018 00:00:03,102\r\n" +
                        "27/05/2018 00:00:04,103\r\n" +
                        "27/05/2018 00:00:05,104\r\n" +
                        "27/05/2018 00:00:06,105\r\n" +
                        "27/05/2018 00:00:07,106\r\n" +
                        "27/05/2018 00:00:08,107\r\n" +
                        "27/05/2018 00:00:09,108\r\n" +
                        "27/05/2018 00:00:10,109\r\n" +
                        "27/05/2018 00:00:11,110\r\n" +
                        "27/05/2018 00:00:12,111\r\n" +
                        "27/05/2018 00:00:13,112\r\n" +
                        "27/05/2018 00:00:14,113\r\n" +
                        "27/05/2018 00:00:15,114\r\n" +
                        "27/05/2018 00:00:16,115\r\n" +
                        "27/05/2018 00:00:17,116\r\n" +
                        "27/05/2018 00:00:18,117\r\n" +
                        "27/05/2018 00:00:19,118\r\n" +
                        "27/05/2018 00:00:20,119\r\n" +
                        "27/05/2018 00:00:21,120\r\n" +
                        "\r\n" +
                        "------WebKitFormBoundaryOsOAD9cPKyHuxyBV--",
                NetworkFacadeImpl.INSTANCE,
                true,
                1
        );
    }

    @Test
    public void testImportForceUnknownTimestamp() throws Exception {
        testImport(
                "HTTP/1.1 400 Bad request\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "\r\n" +
                        "2c\r\n" +
                        "{\"status\":\"DATE format pattern is required\"}\r\n" +
                        "00\r\n" +
                        "\r\n",
                "POST /upload?fmt=json&overwrite=true&forceHeader=true&name=clipboard-157200856 HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Content-Length: 832\r\n" +
                        "Accept: */*\r\n" +
                        "Origin: http://localhost:9000\r\n" +
                        "X-Requested-With: XMLHttpRequest\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/77.0.3865.120 Safari/537.36\r\n" +
                        "Sec-Fetch-Mode: cors\r\n" +
                        "Content-Type: multipart/form-data; boundary=----WebKitFormBoundaryOsOAD9cPKyHuxyBV\r\n" +
                        "Sec-Fetch-Site: same-origin\r\n" +
                        "Referer: http://localhost:9000/index.html\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n" +
                        "------WebKitFormBoundaryOsOAD9cPKyHuxyBV\r\n" +
                        "Content-Disposition: form-data; name=\"schema\"\r\n" +
                        "\r\n" +
                        "[{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"},{\"name\":\"bid\",\"type\":\"INT\"}]\r\n" +
                        "------WebKitFormBoundaryOsOAD9cPKyHuxyBV\r\n" +
                        "Content-Disposition: form-data; name=\"data\"\r\n" +
                        "\r\n" +
                        "timestamp,bid\r\n" +
                        "27/05/2018 00:00:01,100\r\n" +
                        "27/05/2018 00:00:02,101\r\n" +
                        "27/05/2018 00:00:03,102\r\n" +
                        "27/05/2018 00:00:04,103\r\n" +
                        "27/05/2018 00:00:05,104\r\n" +
                        "27/05/2018 00:00:06,105\r\n" +
                        "27/05/2018 00:00:07,106\r\n" +
                        "27/05/2018 00:00:08,107\r\n" +
                        "27/05/2018 00:00:09,108\r\n" +
                        "27/05/2018 00:00:10,109\r\n" +
                        "27/05/2018 00:00:11,110\r\n" +
                        "27/05/2018 00:00:12,111\r\n" +
                        "27/05/2018 00:00:13,112\r\n" +
                        "27/05/2018 00:00:14,113\r\n" +
                        "27/05/2018 00:00:15,114\r\n" +
                        "27/05/2018 00:00:16,115\r\n" +
                        "27/05/2018 00:00:17,116\r\n" +
                        "27/05/2018 00:00:18,117\r\n" +
                        "27/05/2018 00:00:19,118\r\n" +
                        "27/05/2018 00:00:20,119\r\n" +
                        "27/05/2018 00:00:21,120\r\n" +
                        "\r\n" +
                        "------WebKitFormBoundaryOsOAD9cPKyHuxyBV--",
                NetworkFacadeImpl.INSTANCE,
                true,
                1
        );
    }

    @Test
    public void testImportDelimiterNotDetected() throws Exception {
        testImport(
                "HTTP/1.1 400 Bad request\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "\r\n" +
                        "31\r\n" +
                        "not enough lines [table=fhv_tripdata_2017-02.csv]\r\n" +
                        "00\r\n" +
                        "\r\n",
                "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "User-Agent: curl/7.64.0\r\n" +
                        "Accept: */*\r\n" +
                        "Content-Length: 437760673\r\n" +
                        "Content-Type: multipart/form-data; boundary=------------------------27d997ca93d2689d\r\n" +
                        "Expect: 100-continue\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"data\"; filename=\"fhv_tripdata_2017-02.csv\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "9988" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d--",
                NetworkFacadeImpl.INSTANCE,
                true,
                1
        );
    }

    @Test
    public void testImportMultipleOnSameConnectionFragmented() throws Exception {
        testImport(
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "\r\n" +
                        "05d7\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|      Location:  |                          fhv_tripdata_2017-02.csv  |        Pattern  | Locale  |    Errors  |\r\n" +
                        "|   Partition by  |                                              NONE  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|   Rows handled  |                                                24  |                 |         |            |\r\n" +
                        "|  Rows imported  |                                                24  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|              0  |                                DispatchingBaseNum  |                   STRING  |         0  |\r\n" +
                        "|              1  |                                    PickupDateTime  |                     DATE  |         0  |\r\n" +
                        "|              2  |                                   DropOffDatetime  |                   STRING  |         0  |\r\n" +
                        "|              3  |                                      PUlocationID  |                   STRING  |         0  |\r\n" +
                        "|              4  |                                      DOlocationID  |                   STRING  |         0  |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "\r\n" +
                        "00\r\n" +
                        "\r\n",
                "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "User-Agent: curl/7.64.0\r\n" +
                        "Accept: */*\r\n" +
                        "Content-Length: 437760673\r\n" +
                        "Content-Type: multipart/form-data; boundary=------------------------27d997ca93d2689d\r\n" +
                        "Expect: 100-continue\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"schema\"; filename=\"schema.json\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "[\r\n" +
                        "  {\r\n" +
                        "    \"name\": \"date\",\r\n" +
                        "    \"type\": \"DATE\",\r\n" +
                        "    \"pattern\": \"d MMMM y.\",\r\n" +
                        "    \"locale\": \"ru-RU\"\r\n" +
                        "  }\r\n" +
                        "]\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"data\"; filename=\"fhv_tripdata_2017-02.csv\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "Dispatching_base_num,Pickup_DateTime,DropOff_datetime,PUlocationID,DOlocationID\r\n" +
                        "B00008,2017-02-01 00:30:00,,,\r\n" +
                        "B00008,2017-02-01 00:40:00,,,\r\n" +
                        "B00009,2017-02-01 00:30:00,,,\r\n" +
                        "B00013,2017-02-01 00:11:00,,,\r\n" +
                        "B00013,2017-02-01 00:41:00,,,\r\n" +
                        "B00013,2017-02-01 00:00:00,,,\r\n" +
                        "B00013,2017-02-01 00:53:00,,,\r\n" +
                        "B00013,2017-02-01 00:44:00,,,\r\n" +
                        "B00013,2017-02-01 00:05:00,,,\r\n" +
                        "B00013,2017-02-01 00:54:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:46:00,,,\r\n" +
                        "B00014,2017-02-01 00:54:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:26:00,,,\r\n" +
                        "B00014,2017-02-01 00:55:00,,,\r\n" +
                        "B00014,2017-02-01 00:47:00,,,\r\n" +
                        "B00014,2017-02-01 00:05:00,,,\r\n" +
                        "B00014,2017-02-01 00:58:00,,,\r\n" +
                        "B00014,2017-02-01 00:33:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d--",
                new NetworkFacadeImpl() {
                    @Override
                    public int send(long fd, long buffer, int bufferLen) {
                        // ensure we do not send more than one byte at a time
                        if (bufferLen > 0) {
                            return super.send(fd, buffer, 1);
                        }
                        return 0;
                    }
                },
                false,
                150
        );
    }

    @Test
    public void testImportMultipleOnSameConnectionSlow() throws Exception {
//        testImport(
//                "HTTP/1.1 200 OK\r\n" +
//                        "Server: questDB/1.0\r\n" +
//                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
//                        "Transfer-Encoding: chunked\r\n" +
//                        "Content-Type: text/plain; charset=utf-8\r\n" +
//                        "\r\n" +
//                        "05d7\r\n" +
//                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
//                        "|      Location:  |                          fhv_tripdata_2017-02.csv  |        Pattern  | Locale  |    Errors  |\r\n" +
//                        "|   Partition by  |                                              NONE  |                 |         |            |\r\n" +
//                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
//                        "|   Rows handled  |                                                24  |                 |         |            |\r\n" +
//                        "|  Rows imported  |                                                24  |                 |         |            |\r\n" +
//                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
//                        "|              0  |                                DispatchingBaseNum  |                   STRING  |         0  |\r\n" +
//                        "|              1  |                                    PickupDateTime  |                     DATE  |         0  |\r\n" +
//                        "|              2  |                                   DropOffDatetime  |                   STRING  |         0  |\r\n" +
//                        "|              3  |                                      PUlocationID  |                   STRING  |         0  |\r\n" +
//                        "|              4  |                                      DOlocationID  |                   STRING  |         0  |\r\n" +
//                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
//                        "\r\n" +
//                        "00\r\n" +
//                        "\r\n",
//                "POST /upload HTTP/1.1\r\n" +
//                        "Host: localhost:9001\r\n" +
//                        "User-Agent: curl/7.64.0\r\n" +
//                        "Accept: */*\r\n" +
//                        "Content-Length: 437760673\r\n" +
//                        "Content-Type: multipart/form-data; boundary=------------------------27d997ca93d2689d\r\n" +
//                        "Expect: 100-continue\r\n" +
//                        "\r\n" +
//                        "--------------------------27d997ca93d2689d\r\n" +
//                        "Content-Disposition: form-data; name=\"schema\"; filename=\"schema.json\"\r\n" +
//                        "Content-Type: application/octet-stream\r\n" +
//                        "\r\n" +
//                        "[\r\n" +
//                        "  {\r\n" +
//                        "    \"name\": \"date\",\r\n" +
//                        "    \"type\": \"DATE\",\r\n" +
//                        "    \"pattern\": \"d MMMM y.\",\r\n" +
//                        "    \"locale\": \"ru-RU\"\r\n" +
//                        "  }\r\n" +
//                        "]\r\n" +
//                        "\r\n" +
//                        "--------------------------27d997ca93d2689d\r\n" +
//                        "Content-Disposition: form-data; name=\"data\"; filename=\"fhv_tripdata_2017-02.csv\"\r\n" +
//                        "Content-Type: application/octet-stream\r\n" +
//                        "\r\n" +
//                        "Dispatching_base_num,Pickup_DateTime,DropOff_datetime,PUlocationID,DOlocationID\r\n" +
//                        "B00008,2017-02-01 00:30:00,,,\r\n" +
//                        "B00008,2017-02-01 00:40:00,,,\r\n" +
//                        "B00009,2017-02-01 00:30:00,,,\r\n" +
//                        "B00013,2017-02-01 00:11:00,,,\r\n" +
//                        "B00013,2017-02-01 00:41:00,,,\r\n" +
//                        "B00013,2017-02-01 00:00:00,,,\r\n" +
//                        "B00013,2017-02-01 00:53:00,,,\r\n" +
//                        "B00013,2017-02-01 00:44:00,,,\r\n" +
//                        "B00013,2017-02-01 00:05:00,,,\r\n" +
//                        "B00013,2017-02-01 00:54:00,,,\r\n" +
//                        "B00014,2017-02-01 00:45:00,,,\r\n" +
//                        "B00014,2017-02-01 00:45:00,,,\r\n" +
//                        "B00014,2017-02-01 00:46:00,,,\r\n" +
//                        "B00014,2017-02-01 00:54:00,,,\r\n" +
//                        "B00014,2017-02-01 00:45:00,,,\r\n" +
//                        "B00014,2017-02-01 00:45:00,,,\r\n" +
//                        "B00014,2017-02-01 00:45:00,,,\r\n" +
//                        "B00014,2017-02-01 00:26:00,,,\r\n" +
//                        "B00014,2017-02-01 00:55:00,,,\r\n" +
//                        "B00014,2017-02-01 00:47:00,,,\r\n" +
//                        "B00014,2017-02-01 00:05:00,,,\r\n" +
//                        "B00014,2017-02-01 00:58:00,,,\r\n" +
//                        "B00014,2017-02-01 00:33:00,,,\r\n" +
//                        "B00014,2017-02-01 00:45:00,,,\r\n" +
//                        "\r\n" +
//                        "--------------------------27d997ca93d2689d--",
//                new NetworkFacadeImpl() {
//                    int totalSent = 0;
//
//                    @Override
//                    public int send(long fd, long buffer, int bufferLen) {
//                        if (bufferLen > 0) {
//                            int result = super.send(fd, buffer, 1);
//                            totalSent += result;
//
//                            // start delaying after 800 bytes
//
//                            if (totalSent > 800) {
//                                LockSupport.parkNanos(10000);
//                            }
//                            return result;
//                        }
//                        return 0;
//                    }
//                }
//        );
        TestUtils.assertMemoryLeak(() -> {
            final String baseDir = temp.getRoot().getAbsolutePath();
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(baseDir, false, false);
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return new int[]{-1, -1, -1};
                }

                @Override
                public int getWorkerCount() {
                    return 3;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });
            try (
                    CairoEngine engine = new CairoEngine(new DefaultCairoConfiguration(baseDir));
                    HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)
            ) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return "/upload";
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new TextImportProcessor(
                                httpConfiguration.getTextImportProcessorConfiguration(),
                                engine
                        );
                    }
                });

                workerPool.start(LOG);

                // send multipart request to server
                final String request = "POST /upload HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "User-Agent: curl/7.64.0\r\n" +
                        "Accept: */*\r\n" +
                        "Content-Length: 437760673\r\n" +
                        "Content-Type: multipart/form-data; boundary=------------------------27d997ca93d2689d\r\n" +
                        "Expect: 100-continue\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"schema\"; filename=\"schema.json\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "[\r\n" +
                        "  {\r\n" +
                        "    \"name\": \"date\",\r\n" +
                        "    \"type\": \"DATE\",\r\n" +
                        "    \"pattern\": \"d MMMM y.\",\r\n" +
                        "    \"locale\": \"ru-RU\"\r\n" +
                        "  }\r\n" +
                        "]\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d\r\n" +
                        "Content-Disposition: form-data; name=\"data\"; filename=\"fhv_tripdata_2017-02.csv\"\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "\r\n" +
                        "Dispatching_base_num,Pickup_DateTime,DropOff_datetime,PUlocationID,DOlocationID\r\n" +
                        "B00008,2017-02-01 00:30:00,,,\r\n" +
                        "B00008,2017-02-01 00:40:00,,,\r\n" +
                        "B00009,2017-02-01 00:30:00,,,\r\n" +
                        "B00013,2017-02-01 00:11:00,,,\r\n" +
                        "B00013,2017-02-01 00:41:00,,,\r\n" +
                        "B00013,2017-02-01 00:00:00,,,\r\n" +
                        "B00013,2017-02-01 00:53:00,,,\r\n" +
                        "B00013,2017-02-01 00:44:00,,,\r\n" +
                        "B00013,2017-02-01 00:05:00,,,\r\n" +
                        "B00013,2017-02-01 00:54:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:46:00,,,\r\n" +
                        "B00014,2017-02-01 00:54:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "B00014,2017-02-01 00:26:00,,,\r\n" +
                        "B00014,2017-02-01 00:55:00,,,\r\n" +
                        "B00014,2017-02-01 00:47:00,,,\r\n" +
                        "B00014,2017-02-01 00:05:00,,,\r\n" +
                        "B00014,2017-02-01 00:58:00,,,\r\n" +
                        "B00014,2017-02-01 00:33:00,,,\r\n" +
                        "B00014,2017-02-01 00:45:00,,,\r\n" +
                        "\r\n" +
                        "--------------------------27d997ca93d2689d--";

                String expectedResponse = "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "\r\n" +
                        "05d7\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|      Location:  |                          fhv_tripdata_2017-02.csv  |        Pattern  | Locale  |    Errors  |\r\n" +
                        "|   Partition by  |                                              NONE  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|   Rows handled  |                                                24  |                 |         |            |\r\n" +
                        "|  Rows imported  |                                                24  |                 |         |            |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "|              0  |                                DispatchingBaseNum  |                   STRING  |         0  |\r\n" +
                        "|              1  |                                    PickupDateTime  |                     DATE  |         0  |\r\n" +
                        "|              2  |                                   DropOffDatetime  |                   STRING  |         0  |\r\n" +
                        "|              3  |                                      PUlocationID  |                   STRING  |         0  |\r\n" +
                        "|              4  |                                      DOlocationID  |                   STRING  |         0  |\r\n" +
                        "+---------------------------------------------------------------------------------------------------------------+\r\n" +
                        "\r\n" +
                        "00\r\n" +
                        "\r\n";


                NetworkFacade nf = new NetworkFacadeImpl() {
                    int totalSent = 0;

                    @Override
                    public int send(long fd, long buffer, int bufferLen) {
                        if (bufferLen > 0) {
                            int result = super.send(fd, buffer, 1);
                            totalSent += result;

                            // start delaying after 800 bytes

                            if (totalSent > 800) {
                                LockSupport.parkNanos(10000);
                            }
                            return result;
                        }
                        return 0;
                    }
                };

                try {
                    sendAndReceive(
                            nf,
                            request,
                            expectedResponse,
                            1,
                            0,
                            false,
                            false
                    );
                } finally {
                    workerPool.halt();
                }
            }
        });
    }

    @Test
    public void testJsonQueryAndDisconnectWithoutWaitingForResult() throws Exception {
        TestUtils.assertMemoryLeak(() -> {

            final NetworkFacade nf = NetworkFacadeImpl.INSTANCE;
            final String baseDir = temp.getRoot().getAbsolutePath();
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(nf, baseDir, 128, false, false);
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return new int[]{-1, -1};
                }

                @Override
                public int getWorkerCount() {
                    return 2;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });
            try (
                    CairoEngine engine = new CairoEngine(new DefaultCairoConfiguration(baseDir));
                    HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)
            ) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return "/query";
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new JsonQueryProcessor(
                                httpConfiguration.getJsonQueryProcessorConfiguration(),
                                engine
                        );
                    }
                });

                workerPool.start(LOG);

                try {
                    // create table with all column types
                    CairoTestUtils.createTestTable(
                            engine.getConfiguration(),
                            30,
                            new Rnd(),
                            new TestRecord.ArrayBinarySequence());

                    // send multipart request to server
                    final String request = "GET /query?query=x HTTP/1.1\r\n" +
                            "Host: localhost:9001\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Cache-Control: max-age=0\r\n" +
                            "Upgrade-Insecure-Requests: 1\r\n" +
                            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                            "Accept-Encoding: gzip, deflate, br\r\n" +
                            "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                            "\r\n";

                    String expectedResponse = "HTTP/1.1 200 OK\r\n" +
                            "Server: questDB/1.0\r\n" +
                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Keep-Alive: timeout=5, max=10000\r\n" +
                            "\r\n" +
                            "68\r\n" +
                            "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"}\r\n" +
                            "72\r\n" +
                            ",{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"}\r\n" +
                            "75\r\n" +
                            ",{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"}\r\n" +
                            "73\r\n" +
                            ",{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[80,24814,-727724771,8920866532787660373,\"-169665660-01-09T01:58:28.119Z\"\r\n" +
                            "70\r\n" +
                            ",\"-51129-02-11T06:38:29.397464Z\",null,null,\"EHNRX\",\"ZSX\",false,[]],[30,32312,-303295973,6854658259142399220,null\r\n" +
                            "7b\r\n" +
                            ",\"273652-10-24T01:16:04.499209Z\",0.38179755,0.9687423277,\"EDRQQ\",\"LOF\",false,[]],[-79,-21442,1985398001,7522482991756933150\r\n" +
                            "7f\r\n" +
                            ",\"279864478-12-31T01:58:35.932Z\",\"20093-07-24T16:56:53.198086Z\",null,0.0538440031,\"HVUVS\",\"OTS\",true,[]],[70,-29572,-1966408995\r\n" +
                            "79\r\n" +
                            ",-2406077911451945242,null,\"-254163-09-17T05:33:54.251307Z\",0.81233966,null,\"IKJSM\",\"SUQ\",false,[]],[-97,15913,2011884585\r\n" +
                            "7b\r\n" +
                            ",4641238585508069993,\"-277437004-09-03T08:55:41.803Z\",\"186548-11-05T05:57:55.827139Z\",0.89989215,0.6583311520,\"ZIMNZ\",\"RMF\"\r\n" +
                            "7f\r\n" +
                            ",false,[]],[-9,5991,-907794648,null,null,null,0.13264287,null,\"OHNZH\",null,false,[]],[-94,30598,-1510166985,6056145309392106540\r\n" +
                            "76\r\n" +
                            ",null,null,0.54669005,null,\"MZVQE\",\"NDC\",true,[]],[-97,-11913,null,750145151786158348,\"-144112168-08-02T20:50:38.542Z\"\r\n" +
                            "72\r\n" +
                            ",\"-279681-08-19T06:26:33.186955Z\",0.8977236,0.5691053034,\"WIFFL\",\"BRO\",false,[]],[58,7132,null,6793615437970356479\r\n" +
                            "77\r\n" +
                            ",\"63572238-04-24T11:00:13.287Z\",\"171291-08-24T10:16:32.229138Z\",null,0.7215959172,\"KWZLU\",\"GXH\",false,[]],[37,7618,null\r\n" +
                            "7e\r\n" +
                            ",-9219078548506735248,\"286623354-12-11T19:15:45.735Z\",\"197633-02-20T09:12:49.579955Z\",null,0.8001632261,null,\"KFM\",false,[]],[\r\n" +
                            "7e\r\n" +
                            "109,-8207,-485549586,null,\"278802275-11-05T23:22:18.593Z\",\"122137-10-05T20:22:21.831563Z\",0.5780819,0.1858643558,\"DYOPH\",\"IMY\"\r\n" +
                            "80\r\n" +
                            ",false,[]],[-44,21057,-1604266757,4598876523645326656,null,\"204480-04-27T20:21:01.380246Z\",0.19736767,0.1159185576,\"DMIGQ\",\"VKH\"\r\n" +
                            "7c\r\n" +
                            ",false,[]],[17,23522,-861621212,-6446120489339099836,null,\"79287-08-03T02:05:46.962686Z\",0.4349324,0.1129625732,\"CGFNW\",null\r\n" +
                            "7c\r\n" +
                            ",true,[]],[-104,12160,1772084256,-5828188148408093893,\"-270365729-01-24T04:33:47.165Z\",\"-252298-10-09T07:11:36.011048Z\",null\r\n" +
                            "7f\r\n" +
                            ",0.5764439692,\"BQQEM\",null,false,[]],[-99,-7837,-159178348,null,\"81404961-06-19T18:10:11.037Z\",null,0.5598187,0.5900836402,null\r\n" +
                            "7b\r\n" +
                            ",\"HPZ\",true,[]],[-127,5343,-238129044,-8851773155849999621,\"-152632412-11-30T22:15:09.334Z\",\"-90192-03-24T17:45:15.784841Z\"\r\n" +
                            "74\r\n" +
                            ",0.7806183,null,\"CLNXF\",\"UWP\",false,[]],[-59,-10912,1665107665,-8306574409611146484,\"-243146933-02-10T16:15:15.931Z\"\r\n" +
                            "71\r\n" +
                            ",\"-109765-04-18T07:45:05.739795Z\",0.52387,null,\"NIJEE\",\"RUG\",true,[]],[69,4771,21764960,-5708280760166173503,null\r\n" +
                            "7f\r\n" +
                            ",\"-248236-04-27T14:06:03.509521Z\",0.77833515,0.5335243841,\"VOCUG\",\"UNE\",false,[]],[56,-17784,null,5637967617527425113,null,null\r\n" +
                            "61\r\n" +
                            ",null,0.5815065874,null,\"EVQ\",true,[]],[58,29019,-416467698,null,\"-175203601-12-02T01:02:02.378Z\"\r\n" +
                            "74\r\n" +
                            ",\"201101-10-20T07:35:25.133598Z\",null,0.7430101995,\"DXCBJ\",null,true,[]],[-11,-23214,1210163254,-7888017038009650608\r\n" +
                            "7c\r\n" +
                            ",\"152525393-08-28T08:19:48.512Z\",\"216070-11-17T13:37:58.936720Z\",null,null,\"JJILL\",\"YMI\",true,[]],[-69,-29912,217564476,null\r\n" +
                            "7a\r\n" +
                            ",\"-102483035-11-11T09:07:30.782Z\",\"-196714-09-04T03:57:56.227221Z\",0.08039439,0.1868426764,\"EUKWM\",\"NZZ\",true,[]],[4,19590\r\n" +
                            "79\r\n" +
                            ",-1505690678,6904166490726350488,\"-218006330-04-21T14:18:39.081Z\",\"283032-05-21T12:20:14.632027Z\",0.23285526,0.2212274795\r\n" +
                            "62\r\n" +
                            ",\"NSSTC\",\"ZUP\",false,[]],[-111,-6531,342159453,8456443351018554474,\"197601854-07-22T06:29:36.718Z\"\r\n" +
                            "7b\r\n" +
                            ",\"-180434-06-04T17:16:49.501207Z\",0.7910659,0.7128505999,\"YQPZG\",\"ZNY\",true,[]],[106,32411,-1426419269,-2990992799558673548\r\n" +
                            "6a\r\n" +
                            ",\"261692520-06-19T20:19:43.556Z\",null,0.8377384,0.0263363978,\"GENFE\",\"WWR\",false,[]],[-125,25715,null,null\r\n" +
                            "6e\r\n" +
                            ",\"-113894547-06-20T07:24:13.689Z\",null,0.7417434,0.6288088088,\"IJZZY\",null,true,[]],[96,-13602,1350628163,null\r\n" +
                            "6f\r\n" +
                            ",\"257134407-03-20T11:25:44.819Z\",null,0.7360581,null,\"LGYDO\",\"NLI\",true,[]],[-64,8270,null,-5695137753964242205\r\n" +
                            "76\r\n" +
                            ",\"289246073-05-28T15:10:38.644Z\",\"-220112-01-30T11:56:06.194709Z\",0.938019,null,\"GHLXG\",\"MDJ\",true,[]],[-76,12479,null\r\n" +
                            "80\r\n" +
                            ",-4034810129069646757,\"123619904-08-31T19:44:11.844Z\",\"267826-03-17T13:36:32.811014Z\",0.8463546,null,\"PFOYM\",\"WDS\",true,[]],[100\r\n" +
                            "80\r\n" +
                            ",24045,-2102123220,-7175695171900374773,\"-242871073-08-17T14:45:16.399Z\",\"125517-01-13T08:03:16.581566Z\",0.20179749,0.4293443705\r\n" +
                            "25\r\n" +
                            ",\"USIMY\",\"XUU\",false,[]]],\"count\":30}\r\n" +
                            "00\r\n" +
                            "\r\n";

                    sendAndReceive(nf, request, expectedResponse, 10, 100L, false, false);
                } finally {
                    workerPool.halt();
                }
            }
        });
    }

    @Test
    public void testJsonQueryBottomLimit() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x&limit=10,25 HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "070e\r\n" +
                        "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[37,7618,null,-9219078548506735248,\"286623354-12-11T19:15:45.735Z\",\"197633-02-20T09:12:49.579955Z\",null,0.8001632261,null,\"KFM\",false,[]],[109,-8207,-485549586,null,\"278802275-11-05T23:22:18.593Z\",\"122137-10-05T20:22:21.831563Z\",0.5780819,0.1858643558,\"DYOPH\",\"IMY\",false,[]],[-44,21057,-1604266757,4598876523645326656,null,\"204480-04-27T20:21:01.380246Z\",0.19736767,0.1159185576,\"DMIGQ\",\"VKH\",false,[]],[17,23522,-861621212,-6446120489339099836,null,\"79287-08-03T02:05:46.962686Z\",0.4349324,0.1129625732,\"CGFNW\",null,true,[]],[-104,12160,1772084256,-5828188148408093893,\"-270365729-01-24T04:33:47.165Z\",\"-252298-10-09T07:11:36.011048Z\",null,0.5764439692,\"BQQEM\",null,false,[]],[-99,-7837,-159178348,null,\"81404961-06-19T18:10:11.037Z\",null,0.5598187,0.5900836402,null,\"HPZ\",true,[]],[-127,5343,-238129044,-8851773155849999621,\"-152632412-11-30T22:15:09.334Z\",\"-90192-03-24T17:45:15.784841Z\",0.7806183,null,\"CLNXF\",\"UWP\",false,[]],[-59,-10912,1665107665,-8306574409611146484,\"-243146933-02-10T16:15:15.931Z\",\"-109765-04-18T07:45:05.739795Z\",0.52387,null,\"NIJEE\",\"RUG\",true,[]],[69,4771,21764960,-5708280760166173503,null,\"-248236-04-27T14:06:03.509521Z\",0.77833515,0.5335243841,\"VOCUG\",\"UNE\",false,[]],[56,-17784,null,5637967617527425113,null,null,null,0.5815065874,null,\"EVQ\",true,[]],[58,29019,-416467698,null,\"-175203601-12-02T01:02:02.378Z\",\"201101-10-20T07:35:25.133598Z\",null,0.7430101995,\"DXCBJ\",null,true,[]]],\"count\":20}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryDropTable() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=drop%20table%20x HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0c\r\n" +
                        "{\"ddl\":\"OK\"}\r\n" +
                        "00\r\n" +
                        "\r\n",
                1
        );
    }

    @Test
    public void testJsonQueryCreateTable() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=%0A%0A%0Acreate+table+balances_x+(%0A%09cust_id+int%2C+%0A%09balance_ccy+symbol%2C+%0A%09balance+double%2C+%0A%09status+byte%2C+%0A%09timestamp+timestamp%0A)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                        "Host: localhost:9000\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Accept: */*\r\n" +
                        "X-Requested-With: XMLHttpRequest\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.87 Safari/537.36\r\n" +
                        "Sec-Fetch-Site: same-origin\r\n" +
                        "Sec-Fetch-Mode: cors\r\n" +
                        "Referer: http://localhost:9000/index.html\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0c\r\n" +
                        "{\"ddl\":\"OK\"}\r\n" +
                        "00\r\n" +
                        "\r\n",
                1
        );
    }

    @Test
    public void testJsonQueryEmptyText() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query= HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 400 Bad request\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "31\r\n" +
                        "{\"query\":\"\",\"error\":\"No query text\",\"position\":0}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryCreateInsertTruncateAndDrop() throws Exception {
        testJsonQuery0(1, engine -> {

            // create table
            sendAndReceive(
                    NetworkFacadeImpl.INSTANCE,
                    "GET /query?query=%0A%0A%0Acreate+table+balances_x+(%0A%09cust_id+int%2C+%0A%09balance_ccy+symbol%2C+%0A%09balance+double%2C+%0A%09status+byte%2C+%0A%09timestamp+timestamp%0A)&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                            "Host: localhost:9000\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Accept: */*\r\n" +
                            "X-Requested-With: XMLHttpRequest\r\n" +
                            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.87 Safari/537.36\r\n" +
                            "Sec-Fetch-Site: same-origin\r\n" +
                            "Sec-Fetch-Mode: cors\r\n" +
                            "Referer: http://localhost:9000/index.html\r\n" +
                            "Accept-Encoding: gzip, deflate, br\r\n" +
                            "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                            "\r\n",
                    "HTTP/1.1 200 OK\r\n" +
                            "Server: questDB/1.0\r\n" +
                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Keep-Alive: timeout=5, max=10000\r\n" +
                            "\r\n" +
                            "0c\r\n" +
                            "{\"ddl\":\"OK\"}\r\n" +
                            "00\r\n" +
                            "\r\n",
                    1,
                    0,
                    false,
                    false
            );

            // insert one record
            sendAndReceive(
                    NetworkFacadeImpl.INSTANCE,
                    "GET /query?query=%0A%0Ainsert+into+balances_x+(cust_id%2C+balance_ccy%2C+balance%2C+timestamp)+values+(1%2C+%27USD%27%2C+1500.00%2C+to_timestamp(6000000001))&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                            "Host: localhost:9000\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Accept: */*\r\n" +
                            "X-Requested-With: XMLHttpRequest\r\n" +
                            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.87 Safari/537.36\r\n" +
                            "Sec-Fetch-Site: same-origin\r\n" +
                            "Sec-Fetch-Mode: cors\r\n" +
                            "Referer: http://localhost:9000/index.html\r\n" +
                            "Accept-Encoding: gzip, deflate, br\r\n" +
                            "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                            "\r\n",
                    "HTTP/1.1 200 OK\r\n" +
                            "Server: questDB/1.0\r\n" +
                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Keep-Alive: timeout=5, max=10000\r\n" +
                            "\r\n" +
                            "0c\r\n" +
                            "{\"ddl\":\"OK\"}\r\n" +
                            "00\r\n" +
                            "\r\n",
                    1,
                    0,
                    false,
                    false
            );

            // check if we have one record
            sendAndReceive(
                    NetworkFacadeImpl.INSTANCE,
                    "GET /query?query=%0A%0Aselect+*+from+balances_x+latest+by+cust_id%2C+balance_ccy&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                            "Host: localhost:9000\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Accept: */*\r\n" +
                            "X-Requested-With: XMLHttpRequest\r\n" +
                            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.87 Safari/537.36\r\n" +
                            "Sec-Fetch-Site: same-origin\r\n" +
                            "Sec-Fetch-Mode: cors\r\n" +
                            "Referer: http://localhost:9000/index.html\r\n" +
                            "Accept-Encoding: gzip, deflate, br\r\n" +
                            "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                            "\r\n",
                    "HTTP/1.1 200 OK\r\n" +
                            "Server: questDB/1.0\r\n" +
                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Keep-Alive: timeout=5, max=10000\r\n" +
                            "\r\n" +
                            "0155\r\n" +
                            "{\"query\":\"\\n\\nselect * from balances_x latest by cust_id, balance_ccy\",\"columns\":[{\"name\":\"cust_id\",\"type\":\"INT\"},{\"name\":\"balance_ccy\",\"type\":\"SYMBOL\"},{\"name\":\"balance\",\"type\":\"DOUBLE\"},{\"name\":\"status\",\"type\":\"BYTE\"},{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"}],\"dataset\":[[1,\"USD\",1500.0000000000,0,\"1970-01-01T01:40:00.000001Z\"]],\"count\":1}\r\n" +
                            "00\r\n" +
                            "\r\n",
                    1,
                    0,
                    false,
                    false
            );

            // truncate table
            sendAndReceive(
                    NetworkFacadeImpl.INSTANCE,
                    "GET /query?query=%0A%0Atruncate+table+balances_x&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                            "Host: localhost:9000\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Accept: */*\r\n" +
                            "X-Requested-With: XMLHttpRequest\r\n" +
                            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.87 Safari/537.36\r\n" +
                            "Sec-Fetch-Site: same-origin\r\n" +
                            "Sec-Fetch-Mode: cors\r\n" +
                            "Referer: http://localhost:9000/index.html\r\n" +
                            "Accept-Encoding: gzip, deflate, br\r\n" +
                            "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                            "\r\n",
                    "HTTP/1.1 200 OK\r\n" +
                            "Server: questDB/1.0\r\n" +
                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Keep-Alive: timeout=5, max=10000\r\n" +
                            "\r\n" +
                            "0c\r\n" +
                            "{\"ddl\":\"OK\"}\r\n" +
                            "00\r\n" +
                            "\r\n",
                    1,
                    0,
                    false,
                    false
            );

            // select again expecting only metadata
            sendAndReceive(
                    NetworkFacadeImpl.INSTANCE,
                    "GET /query?query=%0A%0Aselect+*+from+balances_x+latest+by+cust_id%2C+balance_ccy&limit=0%2C1000&count=true HTTP/1.1\r\n" +
                            "Host: localhost:9000\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Accept: */*\r\n" +
                            "X-Requested-With: XMLHttpRequest\r\n" +
                            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.87 Safari/537.36\r\n" +
                            "Sec-Fetch-Site: same-origin\r\n" +
                            "Sec-Fetch-Mode: cors\r\n" +
                            "Referer: http://localhost:9000/index.html\r\n" +
                            "Accept-Encoding: gzip, deflate, br\r\n" +
                            "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                            "\r\n",
                    "HTTP/1.1 200 OK\r\n" +
                            "Server: questDB/1.0\r\n" +
                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Keep-Alive: timeout=5, max=10000\r\n" +
                            "\r\n" +
                            "011c\r\n" +
                            "{\"query\":\"\\n\\nselect * from balances_x latest by cust_id, balance_ccy\",\"columns\":[{\"name\":\"cust_id\",\"type\":\"INT\"},{\"name\":\"balance_ccy\",\"type\":\"SYMBOL\"},{\"name\":\"balance\",\"type\":\"DOUBLE\"},{\"name\":\"status\",\"type\":\"BYTE\"},{\"name\":\"timestamp\",\"type\":\"TIMESTAMP\"}],\"dataset\":[],\"count\":0}\r\n" +
                            "00\r\n" +
                            "\r\n",
                    1,
                    0,
                    false,
                    false
            );
        });
    }

    @Test
    public void testJsonQueryMiddleLimit() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x&limit=10,14 HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "042b\r\n" +
                        "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[37,7618,null,-9219078548506735248,\"286623354-12-11T19:15:45.735Z\",\"197633-02-20T09:12:49.579955Z\",null,0.8001632261,null,\"KFM\",false,[]],[109,-8207,-485549586,null,\"278802275-11-05T23:22:18.593Z\",\"122137-10-05T20:22:21.831563Z\",0.5780819,0.1858643558,\"DYOPH\",\"IMY\",false,[]],[-44,21057,-1604266757,4598876523645326656,null,\"204480-04-27T20:21:01.380246Z\",0.19736767,0.1159185576,\"DMIGQ\",\"VKH\",false,[]],[17,23522,-861621212,-6446120489339099836,null,\"79287-08-03T02:05:46.962686Z\",0.4349324,0.1129625732,\"CGFNW\",null,true,[]],[-104,12160,1772084256,-5828188148408093893,\"-270365729-01-24T04:33:47.165Z\",\"-252298-10-09T07:11:36.011048Z\",null,0.5764439692,\"BQQEM\",null,false,[]]],\"count\":14}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryMiddleLimitNoMeta() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x&limit=10,14&nm=true HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "02be\r\n" +
                        "{\"dataset\":[[37,7618,null,-9219078548506735248,\"286623354-12-11T19:15:45.735Z\",\"197633-02-20T09:12:49.579955Z\",null,0.8001632261,null,\"KFM\",false,[]],[109,-8207,-485549586,null,\"278802275-11-05T23:22:18.593Z\",\"122137-10-05T20:22:21.831563Z\",0.5780819,0.1858643558,\"DYOPH\",\"IMY\",false,[]],[-44,21057,-1604266757,4598876523645326656,null,\"204480-04-27T20:21:01.380246Z\",0.19736767,0.1159185576,\"DMIGQ\",\"VKH\",false,[]],[17,23522,-861621212,-6446120489339099836,null,\"79287-08-03T02:05:46.962686Z\",0.4349324,0.1129625732,\"CGFNW\",null,true,[]],[-104,12160,1772084256,-5828188148408093893,\"-270365729-01-24T04:33:47.165Z\",\"-252298-10-09T07:11:36.011048Z\",null,0.5764439692,\"BQQEM\",null,false,[]]],\"count\":14}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryMultipleRows() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0b86\r\n" +
                        "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[80,24814,-727724771,8920866532787660373,\"-169665660-01-09T01:58:28.119Z\",\"-51129-02-11T06:38:29.397464Z\",null,null,\"EHNRX\",\"ZSX\",false,[]],[30,32312,-303295973,6854658259142399220,null,\"273652-10-24T01:16:04.499209Z\",0.38179755,0.9687423277,\"EDRQQ\",\"LOF\",false,[]],[-79,-21442,1985398001,7522482991756933150,\"279864478-12-31T01:58:35.932Z\",\"20093-07-24T16:56:53.198086Z\",null,0.0538440031,\"HVUVS\",\"OTS\",true,[]],[70,-29572,-1966408995,-2406077911451945242,null,\"-254163-09-17T05:33:54.251307Z\",0.81233966,null,\"IKJSM\",\"SUQ\",false,[]],[-97,15913,2011884585,4641238585508069993,\"-277437004-09-03T08:55:41.803Z\",\"186548-11-05T05:57:55.827139Z\",0.89989215,0.6583311520,\"ZIMNZ\",\"RMF\",false,[]],[-9,5991,-907794648,null,null,null,0.13264287,null,\"OHNZH\",null,false,[]],[-94,30598,-1510166985,6056145309392106540,null,null,0.54669005,null,\"MZVQE\",\"NDC\",true,[]],[-97,-11913,null,750145151786158348,\"-144112168-08-02T20:50:38.542Z\",\"-279681-08-19T06:26:33.186955Z\",0.8977236,0.5691053034,\"WIFFL\",\"BRO\",false,[]],[58,7132,null,6793615437970356479,\"63572238-04-24T11:00:13.287Z\",\"171291-08-24T10:16:32.229138Z\",null,0.7215959172,\"KWZLU\",\"GXH\",false,[]],[37,7618,null,-9219078548506735248,\"286623354-12-11T19:15:45.735Z\",\"197633-02-20T09:12:49.579955Z\",null,0.8001632261,null,\"KFM\",false,[]],[109,-8207,-485549586,null,\"278802275-11-05T23:22:18.593Z\",\"122137-10-05T20:22:21.831563Z\",0.5780819,0.1858643558,\"DYOPH\",\"IMY\",false,[]],[-44,21057,-1604266757,4598876523645326656,null,\"204480-04-27T20:21:01.380246Z\",0.19736767,0.1159185576,\"DMIGQ\",\"VKH\",false,[]],[17,23522,-861621212,-6446120489339099836,null,\"79287-08-03T02:05:46.962686Z\",0.4349324,0.1129625732,\"CGFNW\",null,true,[]],[-104,12160,1772084256,-5828188148408093893,\"-270365729-01-24T04:33:47.165Z\",\"-252298-10-09T07:11:36.011048Z\",null,0.5764439692,\"BQQEM\",null,false,[]],[-99,-7837,-159178348,null,\"81404961-06-19T18:10:11.037Z\",null,0.5598187,0.5900836402,null,\"HPZ\",true,[]],[-127,5343,-238129044,-8851773155849999621,\"-152632412-11-30T22:15:09.334Z\",\"-90192-03-24T17:45:15.784841Z\",0.7806183,null,\"CLNXF\",\"UWP\",false,[]],[-59,-10912,1665107665,-8306574409611146484,\"-243146933-02-10T16:15:15.931Z\",\"-109765-04-18T07:45:05.739795Z\",0.52387,null,\"NIJEE\",\"RUG\",true,[]],[69,4771,21764960,-5708280760166173503,null,\"-248236-04-27T14:06:03.509521Z\",0.77833515,0.5335243841,\"VOCUG\",\"UNE\",false,[]],[56,-17784,null,5637967617527425113,null,null,null,0.5815065874,null,\"EVQ\",true,[]],[58,29019,-416467698,null,\"-175203601-12-02T01:02:02.378Z\",\"201101-10-20T07:35:25.133598Z\",null,0.7430101995,\"DXCBJ\",null,true,[]]],\"count\":20}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryMultipleRowsFiltered() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=%0A%0Aselect+*+from+x+where+i+~%3D+%27E%27&limit=1,1&count=true HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0231\r\n" +
                        "{\"query\":\"\\n\\nselect * from x where i ~= 'E'\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[80,24814,-727724771,8920866532787660373,\"-169665660-01-09T01:58:28.119Z\",\"-51129-02-11T06:38:29.397464Z\",null,null,\"EHNRX\",\"ZSX\",false,[]]],\"count\":5}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryOutsideLimit() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x&limit=35,40 HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0185\r\n" +
                        "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[],\"count\":0}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQuerySingleRow() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x%20where%20i%20%3D%20(%27EHNRX%27) HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0224\r\n" +
                        "{\"query\":\"x where i = ('EHNRX')\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[80,24814,-727724771,8920866532787660373,\"-169665660-01-09T01:58:28.119Z\",\"-51129-02-11T06:38:29.397464Z\",null,null,\"EHNRX\",\"ZSX\",false,[]]],\"count\":1}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQuerySyntaxError() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            final String baseDir = temp.getRoot().getAbsolutePath();
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(baseDir, false, false);
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return new int[]{-1};
                }

                @Override
                public int getWorkerCount() {
                    return 1;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });

            try (
                    CairoEngine engine = new CairoEngine(new DefaultCairoConfiguration(baseDir));
                    HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)
            ) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return "/query";
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new JsonQueryProcessor(
                                httpConfiguration.getJsonQueryProcessorConfiguration(),
                                engine
                        );
                    }
                });

                workerPool.start(LOG);

                // create table with all column types
                CairoTestUtils.createTestTable(
                        engine.getConfiguration(),
                        20,
                        new Rnd(),
                        new TestRecord.ArrayBinarySequence());

                // send multipart request to server
                final String request = "GET /query?query=x%20where2%20i%20%3D%20(%27EHNRX%27) HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n";

                String expectedResponse = "HTTP/1.1 400 Bad request\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "4d\r\n" +
                        "{\"query\":\"x where2 i = ('EHNRX')\",\"error\":\"unexpected token: i\",\"position\":9}\r\n" +
                        "00\r\n" +
                        "\r\n";

                sendAndReceive(
                        NetworkFacadeImpl.INSTANCE,
                        request,
                        expectedResponse,
                        10,
                        0,
                        false,
                        false
                );

                workerPool.halt();
            }
        });
    }

    @Test
    public void testJsonQueryTopLimit() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x&limit=10 HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0687\r\n" +
                        "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[80,24814,-727724771,8920866532787660373,\"-169665660-01-09T01:58:28.119Z\",\"-51129-02-11T06:38:29.397464Z\",null,null,\"EHNRX\",\"ZSX\",false,[]],[30,32312,-303295973,6854658259142399220,null,\"273652-10-24T01:16:04.499209Z\",0.38179755,0.9687423277,\"EDRQQ\",\"LOF\",false,[]],[-79,-21442,1985398001,7522482991756933150,\"279864478-12-31T01:58:35.932Z\",\"20093-07-24T16:56:53.198086Z\",null,0.0538440031,\"HVUVS\",\"OTS\",true,[]],[70,-29572,-1966408995,-2406077911451945242,null,\"-254163-09-17T05:33:54.251307Z\",0.81233966,null,\"IKJSM\",\"SUQ\",false,[]],[-97,15913,2011884585,4641238585508069993,\"-277437004-09-03T08:55:41.803Z\",\"186548-11-05T05:57:55.827139Z\",0.89989215,0.6583311520,\"ZIMNZ\",\"RMF\",false,[]],[-9,5991,-907794648,null,null,null,0.13264287,null,\"OHNZH\",null,false,[]],[-94,30598,-1510166985,6056145309392106540,null,null,0.54669005,null,\"MZVQE\",\"NDC\",true,[]],[-97,-11913,null,750145151786158348,\"-144112168-08-02T20:50:38.542Z\",\"-279681-08-19T06:26:33.186955Z\",0.8977236,0.5691053034,\"WIFFL\",\"BRO\",false,[]],[58,7132,null,6793615437970356479,\"63572238-04-24T11:00:13.287Z\",\"171291-08-24T10:16:32.229138Z\",null,0.7215959172,\"KWZLU\",\"GXH\",false,[]],[37,7618,null,-9219078548506735248,\"286623354-12-11T19:15:45.735Z\",\"197633-02-20T09:12:49.579955Z\",null,0.8001632261,null,\"KFM\",false,[]]],\"count\":10}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryTopLimitAndCount() throws Exception {
        testJsonQuery(
                20,
                "GET /query?query=x&limit=10&count=true HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0687\r\n" +
                        "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[[80,24814,-727724771,8920866532787660373,\"-169665660-01-09T01:58:28.119Z\",\"-51129-02-11T06:38:29.397464Z\",null,null,\"EHNRX\",\"ZSX\",false,[]],[30,32312,-303295973,6854658259142399220,null,\"273652-10-24T01:16:04.499209Z\",0.38179755,0.9687423277,\"EDRQQ\",\"LOF\",false,[]],[-79,-21442,1985398001,7522482991756933150,\"279864478-12-31T01:58:35.932Z\",\"20093-07-24T16:56:53.198086Z\",null,0.0538440031,\"HVUVS\",\"OTS\",true,[]],[70,-29572,-1966408995,-2406077911451945242,null,\"-254163-09-17T05:33:54.251307Z\",0.81233966,null,\"IKJSM\",\"SUQ\",false,[]],[-97,15913,2011884585,4641238585508069993,\"-277437004-09-03T08:55:41.803Z\",\"186548-11-05T05:57:55.827139Z\",0.89989215,0.6583311520,\"ZIMNZ\",\"RMF\",false,[]],[-9,5991,-907794648,null,null,null,0.13264287,null,\"OHNZH\",null,false,[]],[-94,30598,-1510166985,6056145309392106540,null,null,0.54669005,null,\"MZVQE\",\"NDC\",true,[]],[-97,-11913,null,750145151786158348,\"-144112168-08-02T20:50:38.542Z\",\"-279681-08-19T06:26:33.186955Z\",0.8977236,0.5691053034,\"WIFFL\",\"BRO\",false,[]],[58,7132,null,6793615437970356479,\"63572238-04-24T11:00:13.287Z\",\"171291-08-24T10:16:32.229138Z\",null,0.7215959172,\"KWZLU\",\"GXH\",false,[]],[37,7618,null,-9219078548506735248,\"286623354-12-11T19:15:45.735Z\",\"197633-02-20T09:12:49.579955Z\",null,0.8001632261,null,\"KFM\",false,[]]],\"count\":20}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testJsonQueryZeroRows() throws Exception {
        testJsonQuery(
                0,
                "GET /query?query=x HTTP/1.1\r\n" +
                        "Host: localhost:9001\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Cache-Control: max-age=0\r\n" +
                        "Upgrade-Insecure-Requests: 1\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36\r\n" +
                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3\r\n" +
                        "Accept-Encoding: gzip, deflate, br\r\n" +
                        "Accept-Language: en-GB,en-US;q=0.9,en;q=0.8\r\n" +
                        "\r\n",
                "HTTP/1.1 200 OK\r\n" +
                        "Server: questDB/1.0\r\n" +
                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Keep-Alive: timeout=5, max=10000\r\n" +
                        "\r\n" +
                        "0185\r\n" +
                        "{\"query\":\"x\",\"columns\":[{\"name\":\"a\",\"type\":\"BYTE\"},{\"name\":\"b\",\"type\":\"SHORT\"},{\"name\":\"c\",\"type\":\"INT\"},{\"name\":\"d\",\"type\":\"LONG\"},{\"name\":\"e\",\"type\":\"DATE\"},{\"name\":\"f\",\"type\":\"TIMESTAMP\"},{\"name\":\"g\",\"type\":\"FLOAT\"},{\"name\":\"h\",\"type\":\"DOUBLE\"},{\"name\":\"i\",\"type\":\"STRING\"},{\"name\":\"j\",\"type\":\"SYMBOL\"},{\"name\":\"k\",\"type\":\"BOOLEAN\"},{\"name\":\"l\",\"type\":\"BINARY\"}],\"dataset\":[],\"count\":0}\r\n" +
                        "00\r\n" +
                        "\r\n"
        );
    }

    @Test
    public void testMaxConnections() throws Exception {

        LOG.info().$("started maxConnections").$();

        TestUtils.assertMemoryLeak(() -> {
            HttpServerConfiguration httpServerConfiguration = new DefaultHttpServerConfiguration();

            int N = 200;

            AtomicInteger openCount = new AtomicInteger(0);
            AtomicInteger closeCount = new AtomicInteger(0);

            final IODispatcherConfiguration configuration = new DefaultIODispatcherConfiguration() {
                @Override
                public int getActiveConnectionLimit() {
                    return 15;
                }
            };

            try (IODispatcher<HttpConnectionContext> dispatcher = IODispatchers.create(
                    configuration,
                    new IOContextFactory<HttpConnectionContext>() {
                        @Override
                        public HttpConnectionContext newInstance(long fd) {
                            openCount.incrementAndGet();
                            return new HttpConnectionContext(httpServerConfiguration) {
                                @Override
                                public void close() {
                                    closeCount.incrementAndGet();
                                    super.close();
                                }
                            }.of(fd);
                        }
                    }
            )) {
                HttpRequestProcessorSelector selector =
                        new HttpRequestProcessorSelector() {
                            @Override
                            public HttpRequestProcessor select(CharSequence url) {
                                return null;
                            }

                            @Override
                            public HttpRequestProcessor getDefaultProcessor() {
                                return new HttpRequestProcessor() {
                                    @Override
                                    public void onHeadersReady(HttpConnectionContext connectionContext) {
                                    }

                                    @Override
                                    public void onRequestComplete(HttpConnectionContext connectionContext, IODispatcher<HttpConnectionContext> dispatcher) {
                                    }
                                };
                            }

                            @Override
                            public void close() {
                            }
                        };

                AtomicBoolean serverRunning = new AtomicBoolean(true);
                SOCountDownLatch serverHaltLatch = new SOCountDownLatch(1);

                new Thread(() -> {
                    do {
                        dispatcher.run();
                        dispatcher.processIOQueue(
                                (operation, context, disp) -> context.handleClientOperation(operation, disp, selector)
                        );
                    } while (serverRunning.get());
                    serverHaltLatch.countDown();
                }).start();


                for (int i = 0; i < N; i++) {
                    long fd = Net.socketTcp(true);
                    long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                    try {
                        Assert.assertTrue(fd > -1);
                        Assert.assertEquals(0, Net.connect(fd, sockAddr));
                        Assert.assertEquals(0, Net.close(fd));
                        LOG.info().$("closed [fd=").$(fd).$(']').$();
                    } finally {
                        Net.freeSockAddr(sockAddr);
                    }
                }

                Assert.assertFalse(configuration.getActiveConnectionLimit() < dispatcher.getConnectionCount());
                serverRunning.set(false);
                serverHaltLatch.await();
            }
        });
    }

    @Test
    public void testSCPConnectDownloadDisconnect() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            final String baseDir = temp.getRoot().getAbsolutePath();
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(baseDir, false, false);
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return new int[]{-1, -1};
                }

                @Override
                public int getWorkerCount() {
                    return 2;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });
            try (HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                workerPool.start(LOG);

                // create 20Mb file in /tmp directory
                try (Path path = new Path().of(baseDir).concat("questdb-temp.txt").$()) {
                    try {
                        Rnd rnd = new Rnd();
                        final int diskBufferLen = 1024 * 1024;

                        writeRandomFile(path, rnd, 122222212222L, diskBufferLen);

//                        httpServer.getStartedLatch().await();

                        long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                        try {
                            int netBufferLen = 4 * 1024;
                            long buffer = Unsafe.calloc(netBufferLen);
                            try {

                                // send request to server to download file we just created
                                final String request = "GET /questdb-temp.txt HTTP/1.1\r\n" +
                                        "Host: localhost:9000\r\n" +
                                        "Connection: keep-alive\r\n" +
                                        "Cache-Control: max-age=0\r\n" +
                                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                                        "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                                        "Accept-Encoding: gzip,deflate,sdch\r\n" +
                                        "Accept-Language: en-US,en;q=0.8\r\n" +
                                        "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                                        "\r\n";

                                String expectedResponseHeader = "HTTP/1.1 200 OK\r\n" +
                                        "Server: questDB/1.0\r\n" +
                                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                                        "Content-Length: 20971520\r\n" +
                                        "Content-Type: text/plain\r\n" +
                                        "ETag: \"122222212222\"\r\n" + // this is last modified timestamp on the file, we set this value when we created file
                                        "\r\n";

                                for (int j = 0; j < 10; j++) {
                                    long fd = Net.socketTcp(true);
                                    Assert.assertTrue(fd > -1);
                                    Assert.assertEquals(0, Net.connect(fd, sockAddr));
                                    try {
                                        sendRequest(request, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, diskBufferLen, expectedResponseHeader, 20971670);
                                    } finally {
                                        Net.close(fd);
                                    }
                                }

                                // send few requests to receive 304
                                final String request2 = "GET /questdb-temp.txt HTTP/1.1\r\n" +
                                        "Host: localhost:9000\r\n" +
                                        "Connection: keep-alive\r\n" +
                                        "Cache-Control: max-age=0\r\n" +
                                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                                        "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                                        "Accept-Encoding: gzip,deflate,sdch\r\n" +
                                        "Accept-Language: en-US,en;q=0.8\r\n" +
                                        "If-None-Match: \"122222212222\"\r\n" + // this header should make static processor return 304
                                        "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                                        "\r\n";

                                String expectedResponseHeader2 = "HTTP/1.1 304 Not Modified\r\n" +
                                        "Server: questDB/1.0\r\n" +
                                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                                        "Content-Type: text/html; charset=utf-8\r\n" +
                                        "\r\n";

                                for (int i = 0; i < 3; i++) {
                                    long fd = Net.socketTcp(true);
                                    Assert.assertTrue(fd > -1);
                                    Assert.assertEquals(0, Net.connect(fd, sockAddr));
                                    try {
                                        sendRequest(request2, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, 0, expectedResponseHeader2, 126);
                                    } finally {
                                        Net.close(fd);
                                    }
                                }

                                // couple more full downloads after 304
                                for (int j = 0; j < 2; j++) {
                                    long fd = Net.socketTcp(true);
                                    Assert.assertTrue(fd > -1);
                                    Assert.assertEquals(0, Net.connect(fd, sockAddr));
                                    try {
                                        sendRequest(request, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, diskBufferLen, expectedResponseHeader, 20971670);
                                    } finally {
                                        Net.close(fd);
                                    }
                                }

                                // get a 404 now
                                final String request3 = "GET /questdb-temp_!.txt HTTP/1.1\r\n" +
                                        "Host: localhost:9000\r\n" +
                                        "Connection: keep-alive\r\n" +
                                        "Cache-Control: max-age=0\r\n" +
                                        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                                        "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                                        "Accept-Encoding: gzip,deflate,sdch\r\n" +
                                        "Accept-Language: en-US,en;q=0.8\r\n" +
                                        "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                                        "\r\n";

                                String expectedResponseHeader3 = "HTTP/1.1 404 Not Found\r\n" +
                                        "Server: questDB/1.0\r\n" +
                                        "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                                        "Transfer-Encoding: chunked\r\n" +
                                        "Content-Type: text/html; charset=utf-8\r\n" +
                                        "\r\n" +
                                        "0b\r\n" +
                                        "Not Found\r\n" +
                                        "\r\n" +
                                        "00\r\n" +
                                        "\r\n";


                                for (int i = 0; i < 4; i++) {
                                    long fd = Net.socketTcp(true);
                                    Assert.assertTrue(fd > -1);
                                    Assert.assertEquals(0, Net.connect(fd, sockAddr));
                                    try {
                                        sendRequest(request3, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, 0, expectedResponseHeader3, expectedResponseHeader3.length());
                                    } finally {
                                        Net.close(fd);
                                    }
                                }

                                // and few more 304s

                                for (int i = 0; i < 3; i++) {
                                    long fd = Net.socketTcp(true);
                                    Assert.assertTrue(fd > -1);
                                    Assert.assertEquals(0, Net.connect(fd, sockAddr));
                                    try {
                                        sendRequest(request2, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, 0, expectedResponseHeader2, 126);
                                    } finally {
                                        Net.close(fd);
                                    }
                                }

                            } finally {
                                Unsafe.free(buffer, netBufferLen);
                            }
                        } finally {
                            Net.freeSockAddr(sockAddr);
                        }
                        workerPool.halt();
                    } finally {
                        Files.remove(path);
                    }
                }
            }
        });
    }

    @Test
    public void testSCPFullDownload() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            final String baseDir = temp.getRoot().getAbsolutePath();
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(baseDir, false, false);
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return new int[]{-1, -1};
                }

                @Override
                public int getWorkerCount() {
                    return 2;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });
            try (HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                workerPool.start(LOG);

                // create 20Mb file in /tmp directory
                try (Path path = new Path().of(baseDir).concat("questdb-temp.txt").$()) {
                    try {
                        Rnd rnd = new Rnd();
                        final int diskBufferLen = 1024 * 1024;

                        writeRandomFile(path, rnd, 122222212222L, diskBufferLen);

                        long fd = Net.socketTcp(true);
                        try {
                            long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                            try {
                                Assert.assertTrue(fd > -1);
                                Assert.assertEquals(0, Net.connect(fd, sockAddr));

                                int netBufferLen = 4 * 1024;
                                long buffer = Unsafe.calloc(netBufferLen);
                                try {

                                    // send request to server to download file we just created
                                    final String request = "GET /questdb-temp.txt HTTP/1.1\r\n" +
                                            "Host: localhost:9000\r\n" +
                                            "Connection: keep-alive\r\n" +
                                            "Cache-Control: max-age=0\r\n" +
                                            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                                            "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                                            "Accept-Encoding: gzip,deflate,sdch\r\n" +
                                            "Accept-Language: en-US,en;q=0.8\r\n" +
                                            "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                                            "\r\n";

                                    String expectedResponseHeader = "HTTP/1.1 200 OK\r\n" +
                                            "Server: questDB/1.0\r\n" +
                                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                                            "Content-Length: 20971520\r\n" +
                                            "Content-Type: text/plain\r\n" +
                                            "ETag: \"122222212222\"\r\n" + // this is last modified timestamp on the file, we set this value when we created file
                                            "\r\n";

                                    for (int j = 0; j < 10; j++) {
                                        sendRequest(request, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, diskBufferLen, expectedResponseHeader, 20971670);
                                    }
//
                                    // send few requests to receive 304
                                    final String request2 = "GET /questdb-temp.txt HTTP/1.1\r\n" +
                                            "Host: localhost:9000\r\n" +
                                            "Connection: keep-alive\r\n" +
                                            "Cache-Control: max-age=0\r\n" +
                                            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                                            "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                                            "Accept-Encoding: gzip,deflate,sdch\r\n" +
                                            "Accept-Language: en-US,en;q=0.8\r\n" +
                                            "If-None-Match: \"122222212222\"\r\n" + // this header should make static processor return 304
                                            "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                                            "\r\n";

                                    String expectedResponseHeader2 = "HTTP/1.1 304 Not Modified\r\n" +
                                            "Server: questDB/1.0\r\n" +
                                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                                            "Content-Type: text/html; charset=utf-8\r\n" +
                                            "\r\n";

                                    for (int i = 0; i < 3; i++) {
                                        sendRequest(request2, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, 0, expectedResponseHeader2, 126);
                                    }

                                    // couple more full downloads after 304
                                    for (int j = 0; j < 2; j++) {
                                        sendRequest(request, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, diskBufferLen, expectedResponseHeader, 20971670);
                                    }

                                    // get a 404 now
                                    final String request3 = "GET /questdb-temp_!.txt HTTP/1.1\r\n" +
                                            "Host: localhost:9000\r\n" +
                                            "Connection: keep-alive\r\n" +
                                            "Cache-Control: max-age=0\r\n" +
                                            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                                            "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                                            "Accept-Encoding: gzip,deflate,sdch\r\n" +
                                            "Accept-Language: en-US,en;q=0.8\r\n" +
                                            "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                                            "\r\n";

                                    String expectedResponseHeader3 = "HTTP/1.1 404 Not Found\r\n" +
                                            "Server: questDB/1.0\r\n" +
                                            "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                                            "Transfer-Encoding: chunked\r\n" +
                                            "Content-Type: text/html; charset=utf-8\r\n" +
                                            "\r\n" +
                                            "0b\r\n" +
                                            "Not Found\r\n" +
                                            "\r\n" +
                                            "00\r\n" +
                                            "\r\n";


                                    for (int i = 0; i < 4; i++) {
                                        sendRequest(request3, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, 0, expectedResponseHeader3, expectedResponseHeader3.length());
                                    }

                                    // and few more 304s

                                    for (int i = 0; i < 3; i++) {
                                        sendRequest(request2, fd, buffer);
                                        assertDownloadResponse(fd, rnd, buffer, netBufferLen, 0, expectedResponseHeader2, 126);
                                    }

                                } finally {
                                    Unsafe.free(buffer, netBufferLen);
                                }
                            } finally {
                                Net.freeSockAddr(sockAddr);
                            }
                        } finally {
                            Net.close(fd);
                            LOG.info().$("closed [fd=").$(fd).$(']').$();
                        }

                        workerPool.halt();
                    } finally {
                        Files.remove(path);
                    }
                }
            }
        });
    }

    @Test
    public void testSendHttpGet() throws Exception {

        LOG.info().$("started testSendHttpGet").$();

        final String request = "GET /status?x=1&a=%26b&c&d=x HTTP/1.1\r\n" +
                "Host: localhost:9000\r\n" +
                "Connection: keep-alive\r\n" +
                "Cache-Control: max-age=0\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                "Accept-Encoding: gzip,deflate,sdch\r\n" +
                "Accept-Language: en-US,en;q=0.8\r\n" +
                "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                "\r\n";

        // the difference between request and expected is url encoding (and ':' padding, which can easily be fixed)
        final String expected = "GET /status?x=1&a=&b&c&d=x HTTP/1.1\r\n" +
                "Host:localhost:9000\r\n" +
                "Connection:keep-alive\r\n" +
                "Cache-Control:max-age=0\r\n" +
                "Accept:text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent:Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                "Accept-Encoding:gzip,deflate,sdch\r\n" +
                "Accept-Language:en-US,en;q=0.8\r\n" +
                "Cookie:textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                "\r\n";

        TestUtils.assertMemoryLeak(() -> {
            HttpServerConfiguration httpServerConfiguration = new DefaultHttpServerConfiguration();

            SOCountDownLatch connectLatch = new SOCountDownLatch(1);
            SOCountDownLatch contextClosedLatch = new SOCountDownLatch(1);
            SOCountDownLatch requestReceivedLatch = new SOCountDownLatch(1);
            AtomicInteger closeCount = new AtomicInteger(0);

            try (IODispatcher<HttpConnectionContext> dispatcher = IODispatchers.create(
                    new DefaultIODispatcherConfiguration(),
                    new IOContextFactory<HttpConnectionContext>() {
                        @Override
                        public HttpConnectionContext newInstance(long fd) {
                            connectLatch.countDown();
                            return new HttpConnectionContext(httpServerConfiguration) {
                                @Override
                                public void close() {
                                    // it is possible that context is closed twice in error
                                    // when crashes occur put debug line here to see how many times
                                    // context is closed
                                    if (closeCount.incrementAndGet() == 1) {
                                        super.close();
                                        contextClosedLatch.countDown();
                                    }
                                }
                            }.of(fd);
                        }
                    }
            )) {
                StringSink sink = new StringSink();

                final HttpRequestProcessorSelector selector =

                        new HttpRequestProcessorSelector() {
                            @Override
                            public HttpRequestProcessor select(CharSequence url) {
                                return new HttpRequestProcessor() {
                                    @Override
                                    public void onHeadersReady(HttpConnectionContext context) {
                                        HttpRequestHeader headers = context.getRequestHeader();
                                        sink.put(headers.getMethodLine());
                                        sink.put("\r\n");
                                        ObjList<CharSequence> headerNames = headers.getHeaderNames();
                                        for (int i = 0, n = headerNames.size(); i < n; i++) {
                                            sink.put(headerNames.getQuick(i)).put(':');
                                            sink.put(headers.getHeader(headerNames.getQuick(i)));
                                            sink.put("\r\n");
                                        }
                                        sink.put("\r\n");
                                        requestReceivedLatch.countDown();
                                    }

                                    @Override
                                    public void onRequestComplete(HttpConnectionContext context, IODispatcher<HttpConnectionContext> dispatcher1) {
                                        dispatcher1.registerChannel(context, IOOperation.READ);
                                    }
                                };
                            }

                            @Override
                            public HttpRequestProcessor getDefaultProcessor() {
                                return null;
                            }

                            @Override
                            public void close() {
                            }
                        };

                AtomicBoolean serverRunning = new AtomicBoolean(true);
                SOCountDownLatch serverHaltLatch = new SOCountDownLatch(1);

                new Thread(() -> {
                    while (serverRunning.get()) {
                        dispatcher.run();
                        dispatcher.processIOQueue(
                                (operation, context, disp) -> context.handleClientOperation(operation, disp, selector)
                        );
                    }
                    serverHaltLatch.countDown();
                }).start();


                long fd = Net.socketTcp(true);
                try {
                    long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                    try {
                        Assert.assertTrue(fd > -1);
                        Assert.assertEquals(0, Net.connect(fd, sockAddr));

                        connectLatch.await();

                        int len = request.length();
                        long buffer = TestUtils.toMemory(request);
                        try {
                            Assert.assertEquals(len, Net.send(fd, buffer, len));
                        } finally {
                            Unsafe.free(buffer, len);
                        }

                        // do not disconnect right away, wait for server to receive the request
                        requestReceivedLatch.await();
                        Assert.assertEquals(0, Net.close(fd));
                        LOG.info().$("closed [fd=").$(fd).$(']').$();

                        contextClosedLatch.await();

                        serverRunning.set(false);
                        serverHaltLatch.await();

                        Assert.assertEquals(0, dispatcher.getConnectionCount());

                        TestUtils.assertEquals(expected, sink);
                    } finally {
                        Net.freeSockAddr(sockAddr);
                    }
                } finally {
                    Net.close(fd);
                }

                Assert.assertEquals(1, closeCount.get());
            }
        });
    }

    @Test
    public void testSendHttpGetAndSimpleResponse() throws Exception {

        LOG.info().$("started testSendHttpGetAndSimpleResponse").$();

        final String request = "GET /status?x=1&a=%26b&c&d=x HTTP/1.1\r\n" +
                "Host: localhost:9000\r\n" +
                "Connection: keep-alive\r\n" +
                "Cache-Control: max-age=0\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                "Accept-Encoding: gzip,deflate,sdch\r\n" +
                "Accept-Language: en-US,en;q=0.8\r\n" +
                "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                "\r\n";

        // the difference between request and expected is url encoding (and ':' padding, which can easily be fixed)
        final String expected = "GET /status?x=1&a=&b&c&d=x HTTP/1.1\r\n" +
                "Host:localhost:9000\r\n" +
                "Connection:keep-alive\r\n" +
                "Cache-Control:max-age=0\r\n" +
                "Accept:text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent:Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                "Accept-Encoding:gzip,deflate,sdch\r\n" +
                "Accept-Language:en-US,en;q=0.8\r\n" +
                "Cookie:textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                "\r\n";

        final String expectedResponse = "HTTP/1.1 200 OK\r\n" +
                "Server: questDB/1.0\r\n" +
                "Date: Thu, 1 Jan 1970 00:00:00 GMT\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "\r\n" +
                "04\r\n" +
                "OK\r\n" +
                "\r\n" +
                "00\r\n" +
                "\r\n";

        TestUtils.assertMemoryLeak(() -> {
            HttpServerConfiguration httpServerConfiguration = new DefaultHttpServerConfiguration() {
                @Override
                public MillisecondClock getClock() {
                    return () -> 0;
                }
            };

            SOCountDownLatch connectLatch = new SOCountDownLatch(1);
            SOCountDownLatch contextClosedLatch = new SOCountDownLatch(1);
            AtomicInteger closeCount = new AtomicInteger(0);

            try (IODispatcher<HttpConnectionContext> dispatcher = IODispatchers.create(
                    new DefaultIODispatcherConfiguration(),
                    new IOContextFactory<HttpConnectionContext>() {
                        @Override
                        public HttpConnectionContext newInstance(long fd) {
                            connectLatch.countDown();
                            return new HttpConnectionContext(httpServerConfiguration) {
                                @Override
                                public void close() {
                                    // it is possible that context is closed twice in error
                                    // when crashes occur put debug line here to see how many times
                                    // context is closed
                                    if (closeCount.incrementAndGet() == 1) {
                                        super.close();
                                        contextClosedLatch.countDown();
                                    }
                                }
                            }.of(fd);
                        }
                    }
            )) {
                StringSink sink = new StringSink();

                final HttpRequestProcessorSelector selector =

                        new HttpRequestProcessorSelector() {

                            @Override
                            public HttpRequestProcessor select(CharSequence url) {
                                return null;
                            }

                            @Override
                            public HttpRequestProcessor getDefaultProcessor() {
                                return new HttpRequestProcessor() {
                                    @Override
                                    public void onHeadersReady(HttpConnectionContext context) {
                                        HttpRequestHeader headers = context.getRequestHeader();
                                        sink.put(headers.getMethodLine());
                                        sink.put("\r\n");
                                        ObjList<CharSequence> headerNames = headers.getHeaderNames();
                                        for (int i = 0, n = headerNames.size(); i < n; i++) {
                                            sink.put(headerNames.getQuick(i)).put(':');
                                            sink.put(headers.getHeader(headerNames.getQuick(i)));
                                            sink.put("\r\n");
                                        }
                                        sink.put("\r\n");
                                    }

                                    @Override
                                    public void onRequestComplete(HttpConnectionContext context, IODispatcher<HttpConnectionContext> dispatcher1) throws PeerDisconnectedException, PeerIsSlowToReadException {
                                        context.simpleResponse().sendStatusWithDefaultMessage(200);
                                        dispatcher1.registerChannel(context, IOOperation.READ);
                                    }
                                };
                            }

                            @Override
                            public void close() {
                            }
                        };

                AtomicBoolean serverRunning = new AtomicBoolean(true);
                SOCountDownLatch serverHaltLatch = new SOCountDownLatch(1);

                new Thread(() -> {
                    while (serverRunning.get()) {
                        dispatcher.run();
                        dispatcher.processIOQueue(
                                (operation, context, disp) -> context.handleClientOperation(operation, disp, selector)
                        );
                    }
                    serverHaltLatch.countDown();
                }).start();


                long fd = Net.socketTcp(true);
                try {
                    long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                    try {
                        Assert.assertTrue(fd > -1);
                        Assert.assertEquals(0, Net.connect(fd, sockAddr));

                        connectLatch.await();

                        int len = request.length();
                        long buffer = TestUtils.toMemory(request);
                        try {
                            Assert.assertEquals(len, Net.send(fd, buffer, len));
                            // read response we expect
                            StringSink sink2 = new StringSink();
                            final int expectedLen = expectedResponse.length();
                            int read = 0;
                            while (read < expectedLen) {
                                int n = Net.recv(fd, buffer, len);
                                Assert.assertTrue(n > 0);

                                for (int i = 0; i < n; i++) {
                                    sink2.put((char) Unsafe.getUnsafe().getByte(buffer + i));
                                }
                                // copy response bytes to sink
                                read += n;
                            }

                            TestUtils.assertEquals(expectedResponse, sink2);
                        } finally {
                            Unsafe.free(buffer, len);
                        }

                        Assert.assertEquals(0, Net.close(fd));
                        LOG.info().$("closed [fd=").$(fd).$(']').$();

                        contextClosedLatch.await();

                        serverRunning.set(false);
                        serverHaltLatch.await();

                        Assert.assertEquals(0, dispatcher.getConnectionCount());

                        TestUtils.assertEquals(expected, sink);
                    } finally {
                        Net.freeSockAddr(sockAddr);
                    }
                } finally {
                    Net.close(fd);
                }

                Assert.assertEquals(1, closeCount.get());
            }
        });
    }

    @Test
    public void testSendTimeout() throws Exception {

        LOG.info().$("started testSendHttpGet").$();

        final String request = "GET /status?x=1&a=%26b&c&d=x HTTP/1.1\r\n" +
                "Host: localhost:9000\r\n" +
                "Connection: keep-alive\r\n" +
                "Cache-Control: max-age=0\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                "Accept-Encoding: gzip,deflate,sdch\r\n" +
                "Accept-Language: en-US,en;q=0.8\r\n" +
                "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                "\r\n";

        TestUtils.assertMemoryLeak(() -> {
            HttpServerConfiguration httpServerConfiguration = new DefaultHttpServerConfiguration();

            SOCountDownLatch connectLatch = new SOCountDownLatch(1);
            SOCountDownLatch contextClosedLatch = new SOCountDownLatch(1);
            AtomicInteger closeCount = new AtomicInteger(0);

            try (IODispatcher<HttpConnectionContext> dispatcher = IODispatchers.create(
                    new DefaultIODispatcherConfiguration() {
                        @Override
                        public long getIdleConnectionTimeout() {
                            // 0.5s idle timeout
                            return 500;
                        }
                    },
                    new IOContextFactory<HttpConnectionContext>() {
                        @Override
                        public HttpConnectionContext newInstance(long fd) {
                            connectLatch.countDown();
                            return new HttpConnectionContext(httpServerConfiguration) {
                                @Override
                                public void close() {
                                    // it is possible that context is closed twice in error
                                    // when crashes occur put debug line here to see how many times
                                    // context is closed
                                    if (closeCount.incrementAndGet() == 1) {
                                        super.close();
                                        contextClosedLatch.countDown();
                                    }
                                }
                            }.of(fd);
                        }
                    }
            )) {
                StringSink sink = new StringSink();

                HttpRequestProcessorSelector selector = new HttpRequestProcessorSelector() {

                    @Override
                    public HttpRequestProcessor select(CharSequence url) {
                        return null;
                    }

                    @Override
                    public HttpRequestProcessor getDefaultProcessor() {
                        return new HttpRequestProcessor() {
                            @Override
                            public void onHeadersReady(HttpConnectionContext connectionContext) {
                                HttpRequestHeader headers = connectionContext.getRequestHeader();
                                sink.put(headers.getMethodLine());
                                sink.put("\r\n");
                                ObjList<CharSequence> headerNames = headers.getHeaderNames();
                                for (int i = 0, n = headerNames.size(); i < n; i++) {
                                    sink.put(headerNames.getQuick(i)).put(':');
                                    sink.put(headers.getHeader(headerNames.getQuick(i)));
                                    sink.put("\r\n");
                                }
                                sink.put("\r\n");
                            }

                            @Override
                            public void onRequestComplete(HttpConnectionContext connectionContext, IODispatcher<HttpConnectionContext> dispatcher1) {
                                dispatcher1.registerChannel(connectionContext, IOOperation.READ);
                            }
                        };
                    }

                    @Override
                    public void close() {
                    }
                };

                AtomicBoolean serverRunning = new AtomicBoolean(true);
                SOCountDownLatch serverHaltLatch = new SOCountDownLatch(1);

                new Thread(() -> {
                    while (serverRunning.get()) {
                        dispatcher.run();
                        dispatcher.processIOQueue(
                                (operation, context, disp) -> context.handleClientOperation(operation, disp, selector)
                        );
                    }
                    serverHaltLatch.countDown();
                }).start();


                long fd = Net.socketTcp(true);
                try {
                    long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                    try {
                        Assert.assertTrue(fd > -1);
                        Assert.assertEquals(0, Net.connect(fd, sockAddr));
                        Net.setTcpNoDelay(fd, true);

                        connectLatch.await();

                        int len = request.length();
                        long buffer = TestUtils.toMemory(request);
                        try {
                            int part1 = len / 2;
                            Assert.assertEquals(part1, Net.send(fd, buffer, part1));
                            Thread.sleep(1000);
                            Assert.assertEquals(len - part1, Net.send(fd, buffer + part1, len - part1));
                        } finally {
                            Unsafe.free(buffer, len);
                        }

                        contextClosedLatch.await();

                        Assert.assertEquals(0, dispatcher.getConnectionCount());

                        serverRunning.set(false);
                        serverHaltLatch.await();

                        Assert.assertEquals(0, dispatcher.getConnectionCount());

                        // do not close client side before server does theirs
                        Assert.assertTrue(Net.isDead(fd));

                        TestUtils.assertEquals("", sink);
                    } finally {
                        Net.freeSockAddr(sockAddr);
                    }
                } finally {
                    Net.close(fd);
                    LOG.info().$("closed [fd=").$(fd).$(']').$();
                }

                Assert.assertEquals(1, closeCount.get());
            }
        });
    }

    @Test
    // this test is ignore for the time being because it is unstable on OSX and I
    // have not figured out the reason yet. I would like to see if this test
    // runs any different on Linux, just to narrow the problem down to either
    // dispatcher or Http parser.
    public void testTwoThreadsSendTwoThreadsRead() throws Exception {

        LOG.info().$("started testSendHttpGet").$();

        final String request = "GET /status?x=1&a=%26b&c&d=x HTTP/1.1\r\n" +
                "Host: localhost:9000\r\n" +
                "Connection: keep-alive\r\n" +
                "Cache-Control: max-age=0\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                "Accept-Encoding: gzip,deflate,sdch\r\n" +
                "Accept-Language: en-US,en;q=0.8\r\n" +
                "Cookie: textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                "\r\n";

        // the difference between request and expected is url encoding (and ':' padding, which can easily be fixed)
        final String expected = "GET /status?x=1&a=&b&c&d=x HTTP/1.1\r\n" +
                "Host:localhost:9000\r\n" +
                "Connection:keep-alive\r\n" +
                "Cache-Control:max-age=0\r\n" +
                "Accept:text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\r\n" +
                "User-Agent:Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36\r\n" +
                "Accept-Encoding:gzip,deflate,sdch\r\n" +
                "Accept-Language:en-US,en;q=0.8\r\n" +
                "Cookie:textwrapon=false; textautoformat=false; wysiwyg=textarea\r\n" +
                "\r\n";

        final int N = 1000;
        final int serverThreadCount = 2;
        final int senderCount = 2;


        TestUtils.assertMemoryLeak(() -> {
            HttpServerConfiguration httpServerConfiguration = new DefaultHttpServerConfiguration();

            final NetworkFacade nf = NetworkFacadeImpl.INSTANCE;
            final AtomicInteger requestsReceived = new AtomicInteger();

            try (IODispatcher<HttpConnectionContext> dispatcher = IODispatchers.create(
                    new DefaultIODispatcherConfiguration(),
                    fd -> new HttpConnectionContext(httpServerConfiguration).of(fd)
            )) {

                // server will publish status of each request to this queue
                final RingQueue<Status> queue = new RingQueue<>(Status::new, 1024);
                final MPSequence pubSeq = new MPSequence(queue.getCapacity());
                SCSequence subSeq = new SCSequence();
                pubSeq.then(subSeq).then(pubSeq);

                AtomicBoolean serverRunning = new AtomicBoolean(true);

                CountDownLatch serverHaltLatch = new CountDownLatch(serverThreadCount);
                for (int j = 0; j < serverThreadCount; j++) {
                    new Thread(() -> {
                        final StringSink sink = new StringSink();
                        final long responseBuf = Unsafe.malloc(32);
                        Unsafe.getUnsafe().putByte(responseBuf, (byte) 'A');

                        final HttpRequestProcessor processor = new HttpRequestProcessor() {
                            @Override
                            public void onHeadersReady(HttpConnectionContext context) {
                                HttpRequestHeader headers = context.getRequestHeader();
                                sink.clear();
                                sink.put(headers.getMethodLine());
                                sink.put("\r\n");
                                ObjList<CharSequence> headerNames = headers.getHeaderNames();
                                for (int i = 0, n = headerNames.size(); i < n; i++) {
                                    sink.put(headerNames.getQuick(i)).put(':');
                                    sink.put(headers.getHeader(headerNames.getQuick(i)));
                                    sink.put("\r\n");
                                }
                                sink.put("\r\n");

                                boolean result;
                                try {
                                    TestUtils.assertEquals(expected, sink);
                                    result = true;
                                } catch (Exception e) {
                                    result = false;
                                }

                                while (true) {
                                    long cursor = pubSeq.next();
                                    if (cursor < 0) {
                                        continue;
                                    }
                                    queue.get(cursor).valid = result;
                                    pubSeq.done(cursor);
                                    break;
                                }

                                requestsReceived.incrementAndGet();

                                nf.send(context.getFd(), responseBuf, 1);
                            }

                            @Override
                            public void onRequestComplete(HttpConnectionContext connectionContext, IODispatcher<HttpConnectionContext> dispatcher) {
                                connectionContext.clear();

                                // there is interesting situation here, its possible that header is fully
                                // read and there are either more bytes or disconnect lingering
                                dispatcher.disconnect(connectionContext);
                            }
                        };

                        HttpRequestProcessorSelector selector = new HttpRequestProcessorSelector() {

                            @Override
                            public HttpRequestProcessor select(CharSequence url) {
                                return null;
                            }

                            @Override
                            public HttpRequestProcessor getDefaultProcessor() {
                                return processor;
                            }

                            @Override
                            public void close() {
                            }
                        };

                        while (serverRunning.get()) {
                            dispatcher.run();
                            dispatcher.processIOQueue(
                                    (operation, context, disp) -> context.handleClientOperation(operation, disp, selector)
                            );
                        }

                        Unsafe.free(responseBuf, 32);
                        serverHaltLatch.countDown();
                    }).start();
                }

                for (int j = 0; j < senderCount; j++) {
                    int k = j;
                    new Thread(() -> {
                        long sockAddr = Net.sockaddr("127.0.0.1", 9001);
                        try {
                            for (int i = 0; i < N; i++) {
                                long fd = Net.socketTcp(true);
                                try {
                                    Assert.assertTrue(fd > -1);
                                    Assert.assertEquals(0, Net.connect(fd, sockAddr));

                                    int len = request.length();
                                    long buffer = TestUtils.toMemory(request);
                                    try {
                                        Assert.assertEquals(len, Net.send(fd, buffer, len));
                                        Assert.assertEquals("fd=" + fd + ", i=" + i, 1, Net.recv(fd, buffer, 1));
                                        LOG.info().$("i=").$(i).$(", j=").$(k).$();
                                        Assert.assertEquals('A', Unsafe.getUnsafe().getByte(buffer));
                                    } finally {
                                        Unsafe.free(buffer, len);
                                    }
                                } finally {
                                    Net.close(fd);
                                }
                            }
                        } finally {
                            Net.freeSockAddr(sockAddr);
                        }
                    }).start();
                }

                int receiveCount = 0;
                while (receiveCount < N * senderCount) {
                    long cursor = subSeq.next();
                    if (cursor < 0) {
                        continue;
                    }
                    boolean valid = queue.get(cursor).valid;
                    subSeq.done(cursor);
                    Assert.assertTrue(valid);
                    receiveCount++;
                }

                serverRunning.set(false);
                serverHaltLatch.await();
            }
            Assert.assertEquals(N * senderCount, requestsReceived.get());
        });
    }

    @Test
    @Ignore
    public void testUpload() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            final String baseDir = temp.getRoot().getAbsolutePath();
//            final String baseDir = "/home/vlad/dev/123";
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(baseDir, false, false);
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return new int[]{-1};
                }

                @Override
                public int getWorkerCount() {
                    return 1;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });

            try (
                    CairoEngine engine = new CairoEngine(new DefaultCairoConfiguration(baseDir));
                    HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)
            ) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return "/upload";
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new TextImportProcessor(
                                httpConfiguration.getTextImportProcessorConfiguration(),
                                engine
                        );
                    }
                });

                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return "/query";
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new JsonQueryProcessor(
                                httpConfiguration.getJsonQueryProcessorConfiguration(),
                                engine
                        );
                    }
                });


                workerPool.start(LOG);

                Thread.sleep(2000000);
            }
        });
    }

    @NotNull
    private DefaultHttpServerConfiguration createHttpServerConfiguration(
            String baseDir,
            boolean dumpTraffic,
            boolean allowDeflateBeforeSend
    ) {
        return createHttpServerConfiguration(
                NetworkFacadeImpl.INSTANCE,
                baseDir,
                1024 * 1024,
                dumpTraffic,
                allowDeflateBeforeSend
        );
    }

    @NotNull
    private DefaultHttpServerConfiguration createHttpServerConfiguration(
            NetworkFacade nf,
            String baseDir,
            int sendBufferSize,
            boolean dumpTraffic,
            boolean allowDeflateBeforeSend
    ) {
        final IODispatcherConfiguration ioDispatcherConfiguration = new DefaultIODispatcherConfiguration() {
            @Override
            public NetworkFacade getNetworkFacade() {
                return nf;
            }
        };

        return new DefaultHttpServerConfiguration() {
            private final StaticContentProcessorConfiguration staticContentProcessorConfiguration = new StaticContentProcessorConfiguration() {
                @Override
                public FilesFacade getFilesFacade() {
                    return FilesFacadeImpl.INSTANCE;
                }

                @Override
                public CharSequence getIndexFileName() {
                    return null;
                }

                @Override
                public MimeTypesCache getMimeTypesCache() {
                    return mimeTypesCache;
                }

                @Override
                public CharSequence getPublicDirectory() {
                    return baseDir;
                }

                @Override
                public String getKeepAliveHeader() {
                    return null;
                }
            };

            @Override
            public MillisecondClock getClock() {
                return () -> 0;
            }

            @Override
            public IODispatcherConfiguration getDispatcherConfiguration() {
                return ioDispatcherConfiguration;
            }

            @Override
            public StaticContentProcessorConfiguration getStaticContentProcessorConfiguration() {
                return staticContentProcessorConfiguration;
            }

            @Override
            public int getSendBufferSize() {
                return sendBufferSize;
            }

            @Override
            public boolean getDumpNetworkTraffic() {
                return dumpTraffic;
            }

            @Override
            public boolean allowDeflateBeforeSend() {
                return allowDeflateBeforeSend;
            }
        };
    }

    private void sendAndReceive(
            NetworkFacade nf,
            String request,
            String response,
            int requestCount,
            long pauseBetweenSendAndReceive,
            boolean print,
            boolean expectDisconnect
    ) throws InterruptedException {
        long fd = nf.socketTcp(true);
        try {
            long sockAddr = nf.sockaddr("127.0.0.1", 9001);
            try {
                Assert.assertTrue(fd > -1);
                Assert.assertEquals(0, nf.connect(fd, sockAddr));
                Assert.assertEquals(0, nf.setTcpNoDelay(fd, true));

                byte[] expectedResponse = response.getBytes();
                final int len = Math.max(expectedResponse.length, request.length()) * 2;
                long ptr = Unsafe.malloc(len);
                try {
                    for (int j = 0; j < requestCount; j++) {
                        int sent = 0;
                        int reqLen = request.length();
                        Chars.strcpy(request, reqLen, ptr);
                        while (sent < reqLen) {
                            int n = nf.send(fd, ptr + sent, reqLen - sent);
                            Assert.assertTrue(n > -1);
                            sent += n;
                        }

                        if (pauseBetweenSendAndReceive > 0) {
                            Thread.sleep(pauseBetweenSendAndReceive);
                        }
                        // receive response
                        final int expectedToReceive = expectedResponse.length;
                        int received = 0;
                        if (print) {
                            System.out.println("expected");
                            System.out.println(new String(expectedResponse));
                        }
                        boolean disconnected = false;
                        while (received < expectedToReceive) {
                            int n = nf.recv(fd, ptr + received, len - received);
                            if (n > 0) {
//                                dump(ptr + received, n);
                                // compare bytes
                                for (int i = 0; i < n; i++) {
                                    if (print) {
                                        System.out.print((char) Unsafe.getUnsafe().getByte(ptr + received + i));
                                    } else {
                                        if (expectedResponse[received + i] != Unsafe.getUnsafe().getByte(ptr + received + i)) {
                                            Assert.fail("Error at: " + (received + i) + ", local=" + i);
                                        }
                                    }
                                }
                                received += n;
                            } else if (n < 0) {
                                disconnected = true;
                                break;
                            }
                        }
                        if (disconnected && !expectDisconnect) {
                            LOG.error().$("disconnected?").$();
                            Assert.fail();
                        }
                    }
                } finally {
                    Unsafe.free(ptr, len);
                }
            } finally {
                nf.freeSockAddr(sockAddr);
            }
        } finally {
            nf.close(fd);
        }
    }

    private void testJsonQuery(int recordCount, String request, String expectedResponse, int requestCount) throws Exception {
        testJsonQuery0(2, engine -> {
            // create table with all column types
            CairoTestUtils.createTestTable(
                    engine.getConfiguration(),
                    recordCount,
                    new Rnd(),
                    new TestRecord.ArrayBinarySequence());

            sendAndReceive(
                    NetworkFacadeImpl.INSTANCE,
                    request,
                    expectedResponse,
                    requestCount,
                    0,
                    false,
                    false
            );
        });
    }

    private void testJsonQuery0(int workerCount, HttpClientCode code) throws Exception {
        final int[] workerAffinity = new int[workerCount];
        Arrays.fill(workerAffinity, -1);

        TestUtils.assertMemoryLeak(() -> {
            final String baseDir = temp.getRoot().getAbsolutePath();
            final DefaultHttpServerConfiguration httpConfiguration = createHttpServerConfiguration(baseDir, false, false);
            final WorkerPool workerPool = new WorkerPool(new WorkerPoolConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return workerAffinity;
                }

                @Override
                public int getWorkerCount() {
                    return workerCount;
                }

                @Override
                public boolean haltOnError() {
                    return false;
                }
            });

            try (
                    CairoEngine engine = new CairoEngine(new DefaultCairoConfiguration(baseDir));
                    HttpServer httpServer = new HttpServer(httpConfiguration, workerPool, false)
            ) {
                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return HttpServerConfiguration.DEFAULT_PROCESSOR_URL;
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new StaticContentProcessor(httpConfiguration.getStaticContentProcessorConfiguration());
                    }
                });

                httpServer.bind(new HttpRequestProcessorFactory() {
                    @Override
                    public String getUrl() {
                        return "/query";
                    }

                    @Override
                    public HttpRequestProcessor newInstance() {
                        return new JsonQueryProcessor(
                                httpConfiguration.getJsonQueryProcessorConfiguration(),
                                engine
                        );
                    }
                });

                workerPool.start(LOG);

                try {
                    code.run(engine);
                } finally {
                    workerPool.halt();
                }
            }
        });
    }

    private void testJsonQuery(int recordCount, String request, String expectedResponse) throws Exception {
        testJsonQuery(recordCount, request, expectedResponse, 100);
    }

    private void writeRandomFile(Path path, Rnd rnd, long lastModified, int bufLen) {
        if (Files.exists(path)) {
            Assert.assertTrue(Files.remove(path));
        }
        long fd = Files.openAppend(path);

        long buf = Unsafe.malloc(bufLen); // 1Mb buffer
        for (int i = 0; i < bufLen; i++) {
            Unsafe.getUnsafe().putByte(buf + i, rnd.nextByte());
        }

        for (int i = 0; i < 20; i++) {
            Assert.assertEquals(bufLen, Files.append(fd, buf, bufLen));
        }

        Files.close(fd);
        Files.setLastModified(path, lastModified);
        Unsafe.free(buf, bufLen);
    }

    @FunctionalInterface
    private interface HttpClientCode {
        void run(CairoEngine engine) throws InterruptedException;
    }

    private static class HelloContext implements IOContext {
        private final long fd;
        private final long buffer = Unsafe.malloc(1024);
        private final SOCountDownLatch closeLatch;

        public HelloContext(long fd, SOCountDownLatch closeLatch) {
            this.fd = fd;
            this.closeLatch = closeLatch;
        }

        @Override
        public void close() {
            Unsafe.free(buffer, 1024);
            closeLatch.countDown();
        }

        @Override
        public long getFd() {
            return fd;
        }

        @Override
        public boolean invalid() {
            return false;
        }
    }

    static class Status {
        boolean valid;
    }
}