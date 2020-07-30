/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private ServerSocketChannel serverSock = null;

    /**
     * 
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for SocketProcessor objects
     */
    private SynchronizedStack<SocketProcessor> processorCache;

    /**
     * Cache for poller events
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        }catch ( Exception x ) {
            log.error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }


    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * Handling of accepted sockets.
     * 处理接受的套接字
     * Http11ConnectionHandler
     * 在Http11Protocol中实例化对象定义的
     */
    private Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    @Override
    public Handler getHandler() { return handler; }


    /**
     * Poller thread count.
     * 轮询线程的数量
     */
    private int pollerThreadCount = Math.min(2,Runtime.getRuntime().availableProcessors());
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    private long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }
    /**
     * The socket poller.
     */
    private Poller[] pollers = null;
    private AtomicInteger pollerRotater = new AtomicInteger(0);
    /**
     * Return an available poller in true round robin fashion.
     * getPoller0() 会根据设置的poll线程数，返回一个当前可用的 Poller 对象，使用并发原子简单的并发请求
     * @return The next poller in sequence
     */
    public Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    /**
     * Port in use.
     */
    @Override
    public int getLocalPort() {
        ServerSocketChannel ssc = serverSock;
        if (ssc == null) {
            return -1;
        } else {
            ServerSocket s = ssc.socket();
            if (s == null) {
                return -1;
            } else {
                return s.getLocalPort();
            }
        }
    }


    protected void releaseCaches() {
        this.nioChannels.clear();
        this.processorCache.clear();
        if ( handler != null ) handler.recycle();

    }


    // --------------------------------------------------------- Public Methods
    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     *         for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i=0; i<pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     * Calatina.load方法，绑定动作
     * 主要是开启SocketServer
     */
    @Override
    public void bind() throws Exception {
    	//打开服务器套接字通道。 开启一个server socket   serverSock是ServerSocketChannel的实现类ServerSocketChannelImpl对象
        serverSock = ServerSocketChannel.open();
        //ServerSocket属性设置
        socketProperties.setProperties(serverSock.socket());
        //获取地址InetSocketAddress方式，InetAddress判断
        InetSocketAddress addr = (getAddress()!=null?new InetSocketAddress(getAddress(),getPort()):new InetSocketAddress(getPort()));
        //绑定地址，积压量100
        serverSock.socket().bind(addr,getBacklog());
        //配置为ServerSocketChannel为阻塞模式，模仿APR行为时用到
        serverSock.configureBlocking(true); //mimic APR behavior
        //socket超时设置20000
        serverSock.socket().setSoTimeout(getSocketProperties().getSoTimeout());

        // Initialize thread count defaults for acceptor, poller
        // acceptorThread线程数默认是1
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        //pollerThread线程数默认是2
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        
        //pollerThread停止同步工具
        stopLatch = new CountDownLatch(pollerThreadCount);

        // Initialize SSL if needed
        // 如果配置ssl，就初始化ssl
        initialiseSsl();
        //NioSelectorPool选择器打开
        //这一步会创建两个线程BlockPoller去维护Selector称之为辅Selector
        selectorPool.open();
    }

    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     * Calatina.start方法，绑定动作
     */
    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;
            //处理器缓存
            processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getProcessorCache());
            //事件缓存
            eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                            socketProperties.getEventCache());
            //nio通道
            nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getBufferPool());

            //如果server.xml没有执行器，则在这里创建ThreadPoolExecutor
            //Create worker collection
            if ( getExecutor() == null ) {
                createExecutor();
            }

            //初始化connectionLimitLatch，最大连接数是默认是10000
            //AbstractEndpoint connectionLimitLatch=10000,
            initializeConnectionLatch();

            // Start poller threads，内部类
            // 启动poller线程，并启动
            pollers = new Poller[getPollerThreadCount()];
            for (int i=0; i<pollers.length; i++) {
                pollers[i] = new Poller();
                //Thread[http-nio-8080-ClientPoller-0,5,main]
                //Thread[http-nio-8080-ClientPoller-1,5,main]
                //Thread[ajp-nio-8009-ClientPoller-0,5,main]
                //Thread[ajp-nio-8009-ClientPoller-1,5,main]
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-"+i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }
            //Acceptor线程负责接收网络请求，建立连接，连接建立之后，将这个socket连接交给Poller，由Poller来负责执行数据的读取和业务执行
            //
            //启动接收请求的线程
            startAcceptorThreads();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i=0; pollers!=null && i<pollers.length; i++) {
                if (pollers[i]==null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                stopLatch.await(selectorTimeout + 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignore) {
            }
            shutdownExecutor();
            eventCache.clear();
            nioChannels.clear();
            processorCache.clear();
        }

    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for "+new InetSocketAddress(getAddress(),getPort()));
        }
        if (running) {
            stop();
        }
        // Close server socket
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        super.unbind();
        releaseCaches();
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for "+new InetSocketAddress(getAddress(),getPort()));
        }
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    /**
     * Process the specified connection.
     * 处理指定的连接，真正处理连接的地方
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
    	// 处理指定的连接
        try {
            //disable blocking, APR style, we are gonna be polling it
        	//禁用阻塞，APR风格，进行轮询，请求过来非阻塞模式
            socket.configureBlocking(false);
            //将当前的Socket通道变成普通的java的Socket，准备通信接收
            Socket sock = socket.socket();
            //socketProperties属性配置到sock
            socketProperties.setProperties(sock);
            //获取一个NioChannel通道，缓存栈中取出一个NioChannel
            NioChannel channel = nioChannels.pop();
            //NioChannel去装载Socket和SocketBufferHandler,也就是网络氢气套接字和套接字数据缓存处理对象
            if (channel == null) {
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),//8192
                        socketProperties.getAppWriteBufSize(),//8192
                        socketProperties.getDirectBuffer());
                //判断通道是否加了ssl
                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                channel.setIOChannel(socket);
                channel.reset();
            }
            //getPoller0() 会根据设置的poll线程数，返回一个当前可用的 Poller 对象，使用Round robin算法实现一个简单的负载均衡
            //轮询器注册该通道-并发原子控制加1，控制请求具体进入哪个轮询线程
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("",t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(t);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    /**
     * Returns true if a worker thread is available for processing.
     * @return boolean
     */
    protected boolean isWorkerAvailable() {
        return true;
    }


    /**
     * 套接字处理
     */
    @Override
    public void processSocket(SocketWrapperBase<NioChannel> socketWrapper,
            SocketStatus socketStatus, boolean dispatch) {
        processSocket((NioSocketWrapper) socketWrapper, socketStatus, dispatch);
    }

    /**
     * 套接字处理，此方法是NioEndpoint的轮询器线程，将事件处理交给SocketProcessor
     * @param attachment
     * @param status
     * @param dispatch
     * @return
     */
    protected boolean processSocket(NioSocketWrapper attachment, SocketStatus status, boolean dispatch) {
        try {
        	//判断附件是否为空
            if (attachment == null) {
                return false;
            }
            //获取一个已经实例化好的缓存SocketProcessor
            SocketProcessor sc = processorCache.pop();
            //缓存中拿不到的情况，重新实例化
            if ( sc == null ) sc = new SocketProcessor(attachment, status);
            //替换SocketProcessor的attachment，status
            else sc.reset(attachment, status);
            //获取线程执行器ThreadPoolExecutor
            Executor executor = getExecutor();
            //dispatch=true，线程池能获取线程处理
            if (dispatch && executor != null) {
            	//线程池去执行
                executor.execute(sc);
            } else {
            	//获取不到，没有启动线程，直接执行sc线程的的runf方法
                sc.run();
            }
        } catch (RejectedExecutionException ree) {
            log.warn(sm.getString("endpoint.executor.fail", attachment.getSocket()), ree);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }

    @Override
    protected Log getLog() {
        return log;
    }


    // --------------------------------------------------- Acceptor Inner Class
    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     * 侦听传入的TCP/IP连接并将其交给适当的处理器的后台线程
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            // 一直循环，直到收到关闭命令
            while (running) {

                // Loop if endpoint is paused
            	// 如果请求暂停，则循环
                while (paused && running) {
                	//更换当前请求的状态：PAUSED
                    state = AcceptorState.PAUSED;
                    try {
                    	//睡眠50ms
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                //如果当前running状态为false，则跳出
                if (!running) {
                    break;
                }
                //更换当前请求的状态：RUNNING
                state = AcceptorState.RUNNING;

                //建立接收
                try {
                    //if we have reached max connections, wait
                	//如果我们已经到达最大的连接，就得等待，并且抛出异常
                    countUpOrAwaitConnection();

                    SocketChannel socket = null;
                    try {
                        // Accept the next incoming connection from the server
                    	// 接受到此通道套接字的连接。serverSock是阻塞的
                        // socket，这里会阻塞请求
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        //we didn't get a socket
                        countDownConnection();
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // setSocketOptions() will add channel to the poller if successful
                    // 第一步：如果有请求过来，setSocketOptions()将添加通道到轮询器
                    if (running && !paused) {
                    	//
                        if (!setSocketOptions(socket)) {
                            countDownConnection();
                            closeSocket(socket);
                        }
                    } else {
                        countDownConnection();
                        closeSocket(socket);
                    }
                } catch (SocketTimeoutException sx) {
                    // Ignore: Normal condition
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }
    }


    private void closeSocket(SocketChannel socket) {
        try {
            socket.socket().close();
        } catch (IOException ioe)  {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
        try {
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
    }


    // ----------------------------------------------------- Poller Inner Classes

    /**
     *
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent implements Runnable {

        private NioChannel socket;
        private int interestOps;
        private NioSocketWrapper key;

        public PollerEvent(NioChannel ch, NioSocketWrapper k, int intOps) {
            reset(ch, k, intOps);
        }

        public void reset(NioChannel ch, NioSocketWrapper k, int intOps) {
            socket = ch;
            interestOps = intOps;
            key = k;
        }

        public void reset() {
            reset(null, null, 0);
        }

        /**
         * 具体某一次请求，某一个状态就行，轮询器事件线程都会执行此方法
         */
        @Override
        public void run() {
        	//如果当前的key是建立链接状态
        	//第一次进来是OP_REGISTER
            if ( interestOps == OP_REGISTER ) {
                try {
                	//把当前的key注册给poller中的selector对象，准备后续处理。
                	//socket是NioChannel，获取SocketChanel，改变通道的状态为可读。
                	//注册并且形成新状态-OP_READ状态的契约，key为NioSocketWrapper类，在Accepter线程里，定义好了一个PollerEvent事件对象，并嵌入PollerEvent的相关属性。
                	//selector中addKey()方法加入了当前的契约，契约状态，契约处理NioSocketWrapper对象
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, key);
                } catch (Exception x) {
                    log.error("", x);
                }
            } else {
            	//其他状态就是这里
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    boolean cancel = false;
                    if (key != null) {
                        final NioSocketWrapper att = (NioSocketWrapper) key.attachment();
                        if ( att!=null ) {
                            //we are registering the key to start with, reset the fairness counter.
                        	//获取契约的状态
                            int ops = key.interestOps() | interestOps;
                            //设置NioSocketWrapper的侦听器状态
                            att.interestOps(ops);
                            //设置契约的状态
                            key.interestOps(ops);
                        } else {
                            cancel = true;
                        }
                    } else {
                        cancel = true;
                    }
                    //注销key
                    if ( cancel ) socket.getPoller().cancelledKey(key);
                }catch (CancelledKeyException ckx) {
                    try {
                    	 //注销key
                        socket.getPoller().cancelledKey(key);
                    }catch (Exception ignore) {}
                }
            }//end if
        }//run

        @Override
        public String toString() {
            return super.toString()+"[intOps="+this.interestOps+"]";
        }
    }

    /**
     * Poller class.
     */
    public class Poller implements Runnable {

    	//Selector类(侦听器类)，存储所有契约的集合:三个契约SelectionKey集合：all-keys，selected-keys，cancelled-keys.
        private Selector selector;
        //轮询器事件同步队列
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();
        //是否需要关闭此次事件处理
        private volatile boolean close = false;
        
        //优化过期处理
        private long nextExpiration = 0;//optimize expiration handling

        //被唤醒的数量
        private AtomicLong wakeupCounter = new AtomicLong(0);
        
        //key的数量
        private volatile int keyCount = 0;

        public Poller() throws IOException {
        	//创建侦听器。侦听器被创建后直接就是开着的
            this.selector = Selector.open();
        }
        
        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector;}

        /**
         * Destroy the poller.
         * 销毁轮续器
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
        	// 在做任何事情之前等待轮询时间，以便轮询器线程退出，否则仍然在轮询器中的套接字的并行闭包会导致问题
            close = true;
            //wakeup用于唤醒阻塞在select方法上的线程,wakeup调用了write
            selector.wakeup();
        }
        
        //Poller中加轮询事件
        private void addEvent(PollerEvent event) {
            events.offer(event);
            if ( wakeupCounter.incrementAndGet() == 0 ) selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         * 向轮询器添加指定的套接字和关联池。套接字通道将被添加到一个临时数组中，并且在与轮询时间相等的最大时间量之后首先进行轮询(但是，在大多数情况下，延迟会低得多)。
         * @param socket to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.pop();
            if ( r==null) r = new PollerEvent(socket,null,interestOps);
            else r.reset(socket,null,interestOps);
            addEvent(r);
            if (close) {
                NioEndpoint.NioSocketWrapper ka = (NioEndpoint.NioSocketWrapper)socket.getAttachment();
                processSocket(ka, SocketStatus.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         * 在轮询器的事件队列中处理事件
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        public boolean events() {
            boolean result = false;
            //初始化轮询器事件
            PollerEvent pe = null;
            //取出一个轮询器事件
            while ( (pe = events.poll()) != null ) {
                result = true;
                try {
                	//进行时间处理
                    pe.run();
                    //轮询器事件进行复位
                    pe.reset();
                    //复位之后重新插入事件栈中
                    if (running && !paused) {
                        eventCache.push(pe);
                    }
                } catch ( Throwable x ) {
                    log.error("",x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         * 向轮询器注册新创建的套接字。
         * @param socket    The newly created socket
         */
        public void register(final NioChannel socket) {
        	//NioChannel嵌入当前线程
            socket.setPoller(this);
            //新建一个NioSocketWrapper的附件
            //socket用NioSocketWrapper进行封装，用锁进行了读写分离
            NioSocketWrapper ka = new NioSocketWrapper(socket, NioEndpoint.this);
            //向具体的某个通道嵌入附件
            socket.setSocketWrapper(ka);
            //NioSocketWrapper设置Poller线程
            ka.setPoller(this);
            ka.setReadTimeout(getSocketProperties().getSoTimeout());
            ka.setWriteTimeout(getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            ka.setReadTimeout(getSoTimeout());
            ka.setWriteTimeout(getSoTimeout());
            //从缓存中获取一个轮询器事件
            PollerEvent r = eventCache.pop();
            //OP_REGISTER,建立链接就绪之后
            //设置当前Socket要监听的事件是读时间
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            //如果轮询器缓存不存在，则需要自己建立一个PollerEvent时间，状态是OP_REGISTER-->：PollerEvent：OP_REGISTER：interestOps，socket：NioChannel，ka：NioSocketWrapper
            if ( r==null) r = new PollerEvent(socket,ka,OP_REGISTER);
            //重置当前的PollerEvent为OP_REGISTER状态
            else r.reset(socket,ka,OP_REGISTER);
            //加入该事件，PollerEvent携带了具体NioChannel，具体的NioSocketWrapper，具体的NioChannel，NioSocketWrapper都携带了Poller
            //Poller中属性events同步事件中加入轮询器事件
            //注册事件
            addEvent(r);
        }
        
        public NioSocketWrapper cancelledKey(SelectionKey key) {
            NioSocketWrapper ka = null;
            try {
                if ( key == null ) return null;//nothing to do
                ka = (NioSocketWrapper) key.attach(null);
                if (ka!=null) handler.release(ka);
                else handler.release((SocketChannel)key.channel());
                if (key.isValid()) key.cancel();
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka!=null) {
                        ka.getSocket().close(true);
                    }
                } catch (Exception e){
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString(
                                "endpoint.debug.socketCloseFail"), e);
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka != null) {
                    countDownConnection();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("",e);
            }
            return ka;
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         */
        @Override
        public void run() {
            // Loop until destroy() is called
            while (true) {

                boolean hasEvents = false;

                // Time to terminate?
                // 超时中断
                if (close) {
                    events();
                    timeout(0, false);
                    try {
                        selector.close();
                    } catch (IOException ioe) {
                        log.error(sm.getString(
                                "endpoint.nio.selectorCloseFail"), ioe);
                    }
                    break;
                } else {
                	//处理events队列中的一事件，出来成功返回true，处理失败返回false，把没准备好的契约状态进行改改
                	//PollerEvent线程的run方法执行，reset方法执行。
                    hasEvents = events();
                }
                try {
                    if ( !close ) {
                    	//getAndSet是设置旧值返回新值
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            //if we are here, means we have other stuff to do
                            //do a non blocking select
                        	//意味着我们还有其他的东西要做非阻塞选择
                            keyCount = selector.selectNow();
                        } else {
                            keyCount = selector.select(selectorTimeout);
                        }
                        wakeupCounter.set(0);
                    }
                    if (close) {
                        events();
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString(
                                    "endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error("",x);
                    continue;
                }
                //either we timed out or we woke up, process events first
                //要么是超时，要么唤醒，看是否有事件需要处理
                if ( keyCount == 0 ) hasEvents = (hasEvents | events());
                
                //获取处理合同(契约)集合
                Iterator<SelectionKey> iterator =
                    keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                // 遍历已就绪SelectionKey的集合并分派任何活动事件。
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    //将SelectionKey的附件NioSocketWrapper，sk：SelectionKeyImpl attachment：NioSocketWrapper
                    //附件,主要作用是为channel处理提供辅助信息,如上面示例中att为ByteBuffer
                    NioSocketWrapper attachment = (NioSocketWrapper)sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    if (attachment == null) {
                    	//清除该契约
                        iterator.remove();
                    } else {
                    	//清除该契约
                        iterator.remove();
                        //处理契约sk：SelectionKeyImpl attachment：NioSocketWrapper
                        processKey(sk, attachment);
                    }
                }//while

                //process timeouts
                //处理超时情况
                timeout(keyCount,hasEvents);
            }//while
            
            stopLatch.countDown();
        }
        /**
         * 处理SelectionKey
         * @param sk
         * @param attachment
         */
        protected void processKey(SelectionKey sk, NioSocketWrapper attachment) {
            try {
                if ( close ) {
                	//如果轮询器关闭，则注销sk
                    cancelledKey(sk);
                } else if ( sk.isValid() && attachment != null ) {
                	//判断sk是否有效，并且是可读或者可写
                    if (sk.isReadable() || sk.isWritable() ) {
                    	//判断当前请求的类型
                        if ( attachment.getSendfileData() != null ) {
                            processSendfile(sk,attachment, false);
                        } else {
                        	//用方法形式判断，判断NioPoint是否是运行状态
                            if ( isWorkerAvailable() ) {
                                unreg(sk, attachment, sk.readyOps());
                                boolean closeSocket = false;
                                // Read goes before write
                                // 先处理读
                                if (sk.isReadable()) {
                                	//进行读操作
                                    if (!processSocket(attachment, SocketStatus.OPEN_READ, true)) {
                                    	//读取失败，更改套接字状态
                                        closeSocket = true;
                                    }
                                }
                                //后处理写
                                if (!closeSocket && sk.isWritable()) {
                                    if (!processSocket(attachment, SocketStatus.OPEN_WRITE, true)) {
                                    	//写入失败，更改套接字状态
                                        closeSocket = true;
                                    }
                                }
                                //如果读写异常，就注销当前的sk
                                if (closeSocket) {
                                    cancelledKey(sk);
                                }
                            }
                        }
                    }
                } else {
                    //invalid key
                	//无效的sk注销
                    cancelledKey(sk);
                }
            } catch ( CancelledKeyException ckx ) {
            	//注销异常，进行注销
                cancelledKey(sk);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("",t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    if (!f.exists()) {
                        cancelledKey(sk);
                        return SendfileState.ERROR;
                    }
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                sc.setSendFile(true);
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel)?sc:sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining()>0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos,sd.length,wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: "+sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        if (sd.keepAlive) {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            reg(sk,socketWrapper,SelectionKey.OP_READ);
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("Send file connection is being closed");
                            }
                            cancelledKey(sk);
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper.getSocket(),SelectionKey.OP_WRITE);
                    } else {
                        reg(sk,socketWrapper,SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException x) {
                if (log.isDebugEnabled()) log.debug("Unable to complete sendfile request:", x);
                cancelledKey(sk);
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error("", t);
                cancelledKey(sk);
                return SendfileState.ERROR;
            } finally {
                if (sc!=null) sc.setSendFile(false);
            }
        }

        protected void unreg(SelectionKey sk, NioSocketWrapper attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk,attachment,sk.interestOps()& (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            int keycount = 0;
            try {
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper ka = (NioSocketWrapper) key.attachment();
                        if ( ka == null ) {
                            cancelledKey(key); //we don't support any keys without attachments
                        } else if ( ka.getError() != null) {
                            cancelledKey(key);//TODO this is not yet being used
                        } else if ((ka.interestOps()&SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                  (ka.interestOps()&SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            if (close) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate stop calls
                                processKey(key,ka);
                            } else {
                                boolean isTimedOut = false;
                                // Check for read timeout
                                if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                    long delta = now - ka.getLastRead();
                                    long timeout = ka.getReadTimeout();
                                    isTimedOut = timeout > 0 && delta > timeout;
                                }
                                // Check for write timeout
                                if (!isTimedOut && (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                    long delta = now - ka.getLastWrite();
                                    long timeout = ka.getWriteTimeout();
                                    isTimedOut = timeout > 0 && delta > timeout;
                                }
                                if (isTimedOut) {
                                    key.interestOps(0);
                                    ka.interestOps(0); //avoid duplicate timeout calls
                                    cancelledKey(key);
                                }
                            }
                        } else if (ka.isAsync()) {
                            if (close) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate stop calls
                                processKey(key,ka);
                            } else if (ka.getAsyncTimeout() > 0) {
                                if ((now - ka.getLastAsyncStart()) > ka.getAsyncTimeout()) {
                                    // Prevent subsequent timeouts if the timeout event takes a while to process
                                    ka.setAsyncTimeout(0);
                                    processSocket(ka, SocketStatus.TIMEOUT, true);
                                }
                            }
                        }//end if
                    }catch ( CancelledKeyException ckx ) {
                        cancelledKey(key);
                    }
                }//for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

    // ---------------------------------------------------- Key Attachment Class
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;

        private Poller poller = null;
        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            
            pool = endpoint.getSelectorPool();
            //通道缓存读写交给socketBufferHandler
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() { return poller;}
        public void setPoller(Poller poller){this.poller = poller;}
        public int interestOps() { return interestOps;}
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if ( latch==null || latch.getCount() == 0 ) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if ( latch == null || latch.getCount() == 0 ) {
                return new CountDownLatch(cnt);
            }
            else throw new IllegalStateException("Latch must be at count 0 or null.");
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch,cnt);}
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch,cnt);}

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if ( latch == null ) throw new IllegalStateException("Latch cannot be null");
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout,unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch,timeout,unit);}
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch,timeout,unit);}

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData;}

        public void updateLastWrite() { lastWrite = System.currentTimeMillis(); }
        public long getLastWrite() { return lastWrite; }
        public void updateLastRead() { lastRead = System.currentTimeMillis(); }
        public long getLastRead() { return lastRead; }

        /**
         * 读就绪判断
         */
        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }

        /**
         * 读缓存数据,返回读取的长度，数据插入到字节数组
         */
        @Override
        public int read(boolean block, byte[] b, int off, int len)
                throws IOException {
        	
            socketBufferHandler.configureReadBufferForRead();
            ByteBuffer readBuffer = socketBufferHandler.getReadBuffer();
            //返回缓存可用的长度
            int remaining = readBuffer.remaining();

            // Is there enough data in the read buffer to satisfy this request?
            // 读取缓冲区中是否有足够的数据来满足此请求?
            if (remaining >= len) {
                readBuffer.get(b, off, len);
                return len;
            }

            // Copy what data there is in the read buffer to the byte array
            // 将读缓冲区中的数据复制到字节数组
            if (remaining > 0) {
                readBuffer.get(b, off, remaining);
                return remaining;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 * 
                 * 由于缓冲区上次被填充后可能已经到达了更多字节，因此此时可以选择执行非阻塞读取。
                 * 但是，如果读取返回流的末尾，正确地处理这种情况会增加复杂性。
                 * 因此，目前的首选是简单性。
                 */
            }

            // Fill the read buffer as best we can.
            // 尽量填充读缓冲区。
            int nRead = fillReadBuffer(block);
            lastRead = System.currentTimeMillis();

            // Full as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                if (nRead > len) {
                    readBuffer.get(b, off, len);
                    return len;
                } else {
                    readBuffer.get(b, off, nRead);
                    return nRead;
                }
            } else {
                return nRead;
            }
        }

        /**
         * 
         */
        @Override
        public void close() throws IOException {
            NioChannel socket = getSocket();
            if (socket != null) {
                socket.close();
            }
        }
        
        /**
         * 填充读缓存
         * @param block
         * @return
         * @throws IOException
         */
        private int fillReadBuffer(boolean block) throws IOException {
            int nRead;
            NioChannel channel = getSocket();
            socketBufferHandler.configureReadBufferForWrite();
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch ( IOException x ) {
                    // Ignore
                }
                try {
                    NioEndpoint.NioSocketWrapper att =
                            (NioEndpoint.NioSocketWrapper) channel.getAttachment();
                    if (att == null) {
                        throw new IOException("Key must be cancelled.");
                    }
                    nRead = pool.read(socketBufferHandler.getReadBuffer(),
                            channel, selector, att.getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                nRead = channel.read(socketBufferHandler.getReadBuffer());
            }
            return nRead;
        }


        @Override
        protected synchronized void doWriteInternal(boolean block) throws IOException {
            socketBufferHandler.configureWriteBufferForRead();

            long writeTimeout = getWriteTimeout();
            Selector selector = null;
            try {
                selector = pool.get();
            } catch (IOException x) {
                // Ignore
            }
            try {
                pool.write(socketBufferHandler.getWriteBuffer(), getSocket(),
                        selector, writeTimeout, block);
                if (block) {
                    // Make sure we are flushed
                    do {
                        if (getSocket().flush(true, selector, writeTimeout)) break;
                    } while (true);
                }
                lastWrite = System.currentTimeMillis();
            } finally {
                if (selector != null) {
                    pool.put(selector);
                }
            }
            // If there is data left in the buffer the socket will be registered for
            // write further up the stack. This is to ensure the socket is only
            // registered for write once as both container and user code can trigger
            // write registration.
        }


        @Override
        public boolean isReadPending() {
            return false;
        }


        @Override
        public void registerReadInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(
                    getSocket().getPoller().getSelector());
            // Might as well do the first write on this thread
            return getSocket().getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateRemoteHost() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteHost = inetAddr.getHostName();
                if (remoteAddr == null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            remotePort = getSocket().getIOChannel().socket().getPort();
        }


        @Override
        protected void populateLocalName() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localName = inetAddr.getHostName();
            }
        }


        @Override
        protected void populateLocalAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateLocalPort() {
            localPort = getSocket().getIOChannel().socket().getLocalPort();
        }


        /**
         * {@inheritDoc}
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLSession session = ch.getSslEngine().getSession();
                return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
            } else {
                return null;
            }
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                try {
                    sslChannel.rehandshake(getEndpoint().getSoTimeout());
                    ((JSSESupport) sslSupport).setSession(engine.getSession());
                } catch (IOException ioe) {
                    log.warn(sm.getString("http11processor.socket.sslreneg",ioe));
                }
            }
        }

        @Override
        public boolean isWritePending() {
            return false;
        }

        @Override
        public <A> CompletionState read(ByteBuffer[] dsts, int offset,
                int length, boolean block, long timeout, TimeUnit unit,
                A attachment, CompletionCheck check,
                CompletionHandler<Long, ? super A> handler) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException();
        }

        @Override
        public <A> CompletionState write(ByteBuffer[] srcs, int offset,
                int length, boolean block, long timeout, TimeUnit unit,
                A attachment, CompletionCheck check,
                CompletionHandler<Long, ? super A> handler) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException();
        }
    }


    // ------------------------------------------------ Handler Inner Interface

    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler extends AbstractEndpoint.Handler<NioChannel> {
        public void release(SocketChannel socket);
    }


    // ---------------------------------------------- SocketProcessor Inner Class
    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable {

        private NioSocketWrapper ka = null;
        private SocketStatus status = null;

        public SocketProcessor(NioSocketWrapper ka, SocketStatus status) {
            reset(ka, status);
        }

        public void reset(NioSocketWrapper ka, SocketStatus status) {
            this.ka = ka;
            this.status = status;
        }

        //polller线程过来->SocketProcessor
        @Override
        public void run() {
        	//NioSocketWrapper获取附件对象和socket的状态，附件
            NioChannel socket = ka.getSocket();
            if (socket == null) {
                return;
            }
            //获取契约的状态
            SelectionKey key = socket.getIOChannel().keyFor(
                    socket.getPoller().getSelector());
            //socket同步代码块
            synchronized (socket) {
                try {
                	//握手状态
                    int handshake = -1;

                    try {
                        if (key != null) {
                            // For STOP there is no point trying to handshake as the
                            // Poller has been stopped.
                        	// 如果轮询器已经停止了，对于握手已经没有意义了
                            if (socket.isHandshakeComplete() ||
                                    status == SocketStatus.STOP) {
                                handshake = 0;
                            } else {
                            	//进行握手
                                handshake = socket.handshake(
                                        key.isReadable(), key.isWritable());
                                // The handshake process reads/writes from/to the
                                // socket. status may therefore be OPEN_WRITE once
                                // the handshake completes. However, the handshake
                                // happens when the socket is opened so the status
                                // must always be OPEN_READ after it completes. It
                                // is OK to always set this as it is only used if
                                // the handshake completes.
                                status = SocketStatus.OPEN_READ;
                            }
                        }
                    } catch (IOException x) {
                        handshake = -1;
                        if (log.isDebugEnabled()) log.debug("Error during SSL handshake",x);
                    } catch (CancelledKeyException ckx) {
                        handshake = -1;
                    }
                    if (handshake == 0) {
                        SocketState state = SocketState.OPEN;
                        // Process the request from this socket
                        if (status == null) {
                        	//Http11ConnectionHandler
                            state = handler.process(ka, SocketStatus.OPEN_READ);
                        } else {
                            state = handler.process(ka, status);
                        }
                        if (state == SocketState.CLOSED) {
                        	//这里会将NioChannel嵌入到缓存中
                            close(socket, key);
                        }
                    } else if (handshake == -1 ) {
                        close(socket, key);
                    } else {
                        ka.getPoller().add(socket,handshake);
                    }
                } catch (CancelledKeyException cx) {
                    socket.getPoller().cancelledKey(key);
                } catch (VirtualMachineError vme) {
                    ExceptionUtils.handleThrowable(vme);
                } catch (Throwable t) {
                    log.error("", t);
                    socket.getPoller().cancelledKey(key);
                } finally {
                    ka = null;
                    status = null;
                    //return to cache
                    if (running && !paused) {
                        processorCache.push(this);
                    }
                }
            }
        }

        private void close(NioChannel socket, SelectionKey key) {
            try {
                if (socket.getPoller().cancelledKey(key) != null) {
                    // SocketWrapper (attachment) was removed from the
                    // key - recycle the key. This can only happen once
                    // per attempted closure so it is used to determine
                    // whether or not to return the key to the cache.
                    // We do NOT want to do this more than once - see BZ
                    // 57340 / 57943.
                    if (running && !paused) {
                        if (!nioChannels.push(socket)) {
                            socket.free();
                        }
                    }
                }
            } catch (Exception x) {
                log.error("",x);
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class
    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
