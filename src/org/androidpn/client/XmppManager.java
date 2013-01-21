/*
 * Copyright (C) 2010 Moduad Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.androidpn.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketIDFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Registration;
import org.jivesoftware.smack.provider.ProviderManager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Handler;
import android.util.Log;

/**
 * This class is to manage the XMPP connection between client and server.
 * 
 * @author Sehwan Noh (devnoh@gmail.com)
 */
public class XmppManager {

    private static final String LOGTAG = LogUtil.makeLogTag(XmppManager.class);

    private static final String XMPP_RESOURCE_NAME = "AndroidpnClient";

    private Context context;

    private NotificationService.TaskSubmitter taskSubmitter;

    private NotificationService.TaskTracker taskTracker;

    private SharedPreferences sharedPrefs;

    private String xmppHost;

    private int xmppPort;

    private XMPPConnection connection;

    private String username;

    private String password;

    private ConnectionListener connectionListener;

    private PacketListener notificationPacketListener;

    private Handler handler;

    private List<Runnable> taskList;

    private boolean running = false;

    private Future<?> futureTask;

    private Thread reconnection;

    /**
     * XmppManager的构造方法
     * 从共享引用中取得xmpp服务器地址和端口号、用户名和密码
     * 模拟器测试的话服务器地址设置为10.0.2.2
     * @param notificationService
     */
    public XmppManager(NotificationService notificationService) {
        context = notificationService;
        taskSubmitter = notificationService.getTaskSubmitter();
        taskTracker = notificationService.getTaskTracker();
        sharedPrefs = notificationService.getSharedPreferences();

        xmppHost = sharedPrefs.getString(Constants.XMPP_HOST, "localhost");
        xmppPort = sharedPrefs.getInt(Constants.XMPP_PORT, 5222);
        username = sharedPrefs.getString(Constants.XMPP_USERNAME, "");
        password = sharedPrefs.getString(Constants.XMPP_PASSWORD, "");

        connectionListener = new PersistentConnectionListener(this);
        notificationPacketListener = new NotificationPacketListener(this);

        handler = new Handler();
        taskList = new ArrayList<Runnable>();
        /**
         * 重连线程
         */
        reconnection = new ReconnectionThread(this);
    }

    public Context getContext() {
        return context;
    }

    public void connect() {
        Log.d(LOGTAG, "connect()...");
        submitLoginTask();
    }

    public void disconnect() {
        Log.d(LOGTAG, "disconnect()...");
        terminatePersistentConnection();
    }

    /**
     * 终止连接
     */
    public void terminatePersistentConnection() {
        Log.d(LOGTAG, "terminatePersistentConnection()...");
        Runnable runnable = new Runnable() {

            final XmppManager xmppManager = XmppManager.this;

            public void run() {
                if (xmppManager.isConnected()) {
                    Log.d(LOGTAG, "terminatePersistentConnection()... run()");
                    xmppManager.getConnection().removePacketListener(
                            xmppManager.getNotificationPacketListener());  //移除数据包的监听
                    xmppManager.getConnection().disconnect();
                }
                xmppManager.runTask();
            }

        };
        addTask(runnable);
    }

    public XMPPConnection getConnection() {
        return connection;
    }

    public void setConnection(XMPPConnection connection) {
        this.connection = connection;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public ConnectionListener getConnectionListener() {
        return connectionListener;
    }

    public PacketListener getNotificationPacketListener() {
        return notificationPacketListener;
    }

    /**
     * 启动重连线程
     */
    public void startReconnectionThread() {
        synchronized (reconnection) {
            if (!reconnection.isAlive()) {
                reconnection.setName("Xmpp Reconnection Thread");
                reconnection.start();
            }
        }
    }

    public Handler getHandler() {
        return handler;
    }

    public void reregisterAccount() {
        removeAccount();
        submitLoginTask();
        runTask();
    }

    public List<Runnable> getTaskList() {
        return taskList;
    }

    public Future<?> getFutureTask() {
        return futureTask;
    }

   /**
    * 执行队列中的任务
    * 执行该方法后 才真正执行run方法
    */
    public void runTask() {
        Log.d(LOGTAG, "runTask()...");
        synchronized (taskList) {
            running = false;
            futureTask = null;
            if (!taskList.isEmpty()) {
                Runnable runnable = (Runnable) taskList.get(0);
                taskList.remove(0);
                running = true;
                futureTask = taskSubmitter.submit(runnable);
                if (futureTask == null) {
                    taskTracker.decrease();
                }
            }
        }
        taskTracker.decrease();
        Log.d(LOGTAG, "runTask()...done");
    }

    /**
     * 产生随机的UUID
     * @return
     */
    private String newRandomUUID() {
        String uuidRaw = UUID.randomUUID().toString();
        return uuidRaw.replaceAll("-", "");
    }

    /**
     *是否连接
     * @return
     */
    private boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    /**
     * 是否登陆验证
     * @return
     */
    private boolean isAuthenticated() {
        return connection != null && connection.isConnected()
                && connection.isAuthenticated();
    }

    /**
     * 是否已注册
     * @return
     */
    private boolean isRegistered() {
        return sharedPrefs.contains(Constants.XMPP_USERNAME)
                && sharedPrefs.contains(Constants.XMPP_PASSWORD);
    }

    private void submitConnectTask() {
        Log.d(LOGTAG, "submitConnectTask()...");
        addTask(new ConnectTask());
    }

    private void submitRegisterTask() {
        Log.d(LOGTAG, "submitRegisterTask()...");
        submitConnectTask();
        addTask(new RegisterTask());
    }

    private void submitLoginTask() {
        Log.d(LOGTAG, "submitLoginTask()...");
        submitRegisterTask();
        addTask(new LoginTask());
    }

    /**
     * 添加任务到队列
     * @param runnable
     */
    private void addTask(Runnable runnable) {
        Log.d(LOGTAG, "addTask(runnable)... running: "+running);
        taskTracker.increase();
        synchronized (taskList) {
            if (taskList.isEmpty() && !running) {
            	Log.d(LOGTAG, "addTask(runnable)... taskList.isEmpty() && !running");
                running = true;
                futureTask = taskSubmitter.submit(runnable);
                if (futureTask == null) {
                    taskTracker.decrease();
                }
            } else {
                taskList.add(runnable);
            }
        }
        Log.d(LOGTAG, "addTask(runnable)... done");
    }

    private void removeAccount() {
        Editor editor = sharedPrefs.edit();
        editor.remove(Constants.XMPP_USERNAME);
        editor.remove(Constants.XMPP_PASSWORD);
        editor.commit();
    }

    /**
     * 执行顺序：第一步
     * A runnable task to connect the server. 
     * 该内部类主要实现的是连接服务器的操作
     * 
     * 通过connection.connect();实现连接服务器
     */
    private class ConnectTask implements Runnable {

        final XmppManager xmppManager;

        private ConnectTask() {
            this.xmppManager = XmppManager.this;
        }

        public void run() {
            Log.i(LOGTAG, "ConnectTask.run()...");

            if (!xmppManager.isConnected()) {		//未连接到XMPP服务器
                // Create the configuration for this new connection
            	/**
            	 * 设置连接的一些参数
            	 */
                ConnectionConfiguration connConfig = new ConnectionConfiguration(
                        xmppHost, xmppPort);
                // connConfig.setSecurityMode(SecurityMode.disabled);
                connConfig.setSecurityMode(SecurityMode.required);
                connConfig.setSASLAuthenticationEnabled(false);
                connConfig.setCompressionEnabled(false);

                XMPPConnection connection = new XMPPConnection(connConfig);
                xmppManager.setConnection(connection);

                try {
                    // Connect to the server
                    connection.connect();
                    Log.i(LOGTAG, "XMPP connected successfully");

                    // packet provider
                    /** 
                     * 这个就是对于通信的xml文本进行解析的解析器，再把信息转换成IQ，这个相当于QQ的聊天信息 
                     * 如果要用这个协议，其IQ的子类（NotificationIQ）
                     * 和IQProvider的子类（NotificationIQProvider）要进行重写 
                     */
                    ProviderManager.getInstance().addIQProvider("notification",
                            "androidpn:iq:notification",
                            new NotificationIQProvider());

                } catch (XMPPException e) {
                    Log.e(LOGTAG, "XMPP connection failed", e);
                    running = false;
                }
                //执行任务
                xmppManager.runTask();

            } else {
                Log.i(LOGTAG, "XMPP connected already");
                //执行任务
                xmppManager.runTask();
            }
        }
    }

    /**
     * 执行顺序：第二步
     * A runnable task to register a new user onto the server.
     * 该内部类主要实现注册一个新的用户在服务器 
     */
    private class RegisterTask implements Runnable {

        final XmppManager xmppManager;

        private RegisterTask() {
            xmppManager = XmppManager.this;
        }

        public void run() {
            Log.i(LOGTAG, "RegisterTask.run()...");

            if (!xmppManager.isRegistered()) {
                final String newUsername = newRandomUUID();
                final String newPassword = newRandomUUID();
                //客户端发送到服务器注册的数据包，Packet的子类
                Registration registration = new Registration();

                /**
                 * 数据包的ID和类型的过滤器
                 */
                PacketFilter packetFilter = new AndFilter(new PacketIDFilter(
                        registration.getPacketID()), new PacketTypeFilter(
                        IQ.class));
                /**
                 * 数据包的监听
                 */
                PacketListener packetListener = new PacketListener() {

                    public void processPacket(Packet packet) {
                        Log.d("RegisterTask.PacketListener",
                                "processPacket().....");
                        Log.d("RegisterTask.PacketListener", "packet="
                                + packet.toXML());
                        //服务器回复客户端
                        if (packet instanceof IQ) {
                            IQ response = (IQ) packet;
                            if (response.getType() == IQ.Type.ERROR) {		//注册失败
                                if (!response.getError().toString().contains(
                                        "409")) {
                                    Log.e(LOGTAG,
                                            "Unknown error while registering XMPP account! "
                                                    + response.getError()
                                                            .getCondition());
                                }
                            } else if (response.getType() == IQ.Type.RESULT) {  //注册成功
                                xmppManager.setUsername(newUsername);
                                xmppManager.setPassword(newPassword);
                                Log.d(LOGTAG, "username=" + newUsername);
                                Log.d(LOGTAG, "password=" + newPassword);
                                //把用户名和密码保存到共享引用
                                Editor editor = sharedPrefs.edit();
                                editor.putString(Constants.XMPP_USERNAME,
                                        newUsername);
                                editor.putString(Constants.XMPP_PASSWORD,
                                        newPassword);
                                editor.commit();
                                Log.i(LOGTAG,
                                		"Account registered successfully");
                                xmppManager.runTask();
                            }
                        }
                    }
                };
                // 给注册的Packet设置Listener，因为只有等到正真注册成功后，我们才可以交流  
                connection.addPacketListener(packetListener, packetFilter);

                registration.setType(IQ.Type.SET);
                // registration.setTo(xmppHost);
                // Map<String, String> attributes = new HashMap<String, String>();
                // attributes.put("username", rUsername);
                // attributes.put("password", rPassword);
                // registration.setAttributes(attributes);
                registration.addAttribute("username", newUsername);
                registration.addAttribute("password", newPassword);
                
                registration.addAttribute("imsi", "460000001232300");
                registration.addAttribute("imei", "324234343434434");
                
                // 向服务器端，发送注册Packet包，注意其中Registration是Packet的子类 
                connection.sendPacket(registration);

            } else {
                Log.i(LOGTAG, "Account registered already");
                xmppManager.runTask();
            }
        }
    }

    /**
     * 执行顺序：第三步
     * A runnable task to log into the server. 
     * 该内部类主要实现用注册的账户和密码进行登陆
     */
    private class LoginTask implements Runnable {

        final XmppManager xmppManager;

        private LoginTask() {
            this.xmppManager = XmppManager.this;
        }

        public void run() {
            Log.i(LOGTAG, "LoginTask.run()...");
            //判断是否已经登陆过了
            if (!xmppManager.isAuthenticated()) {
                Log.d(LOGTAG, "username=" + username);
                Log.d(LOGTAG, "password=" + password);

                try {
                    xmppManager.getConnection().login(
                            xmppManager.getUsername(),
                            xmppManager.getPassword(), XMPP_RESOURCE_NAME);
                    Log.d(LOGTAG, "Loggedn in successfully");

                    // connection listener
                    /**
                     *  设置XmppConnection的监听器 
                     *  连接过程中有可能连接突然中断，连接出错等等问题，要进行监听 
                     */
                    if (xmppManager.getConnectionListener() != null) {
                        xmppManager.getConnection().addConnectionListener(
                                xmppManager.getConnectionListener());
                    }

                    // packet filter
                    /**
                     * 数据包的过滤器，识别类型是NotificationIQ的数据包
                     */
                    PacketFilter packetFilter = new PacketTypeFilter(
                            NotificationIQ.class);
                    // packet listener
                    /**
                     *   设置服务器端推送信息数据包的监听器 
                     */
                    PacketListener packetListener = xmppManager
                            .getNotificationPacketListener();
                    connection.addPacketListener(packetListener, packetFilter);

                    xmppManager.runTask();

                } catch (XMPPException e) {
                    Log.e(LOGTAG, "LoginTask.run()... xmpp error");
                    Log.e(LOGTAG, "Failed to login to xmpp server. Caused by: "
                            + e.getMessage());
                    /**
                     *  登陆失败，应该重试  
                     */
                    String INVALID_CREDENTIALS_ERROR_CODE = "401";
                    String errorMessage = e.getMessage();
                    /**
                     *  如果只是因为没有注册，则进行重新注册  
                     */
                    if (errorMessage != null
                            && errorMessage
                                    .contains(INVALID_CREDENTIALS_ERROR_CODE)) {
                        xmppManager.reregisterAccount();
                        return;
                    }
                    xmppManager.startReconnectionThread();

                } catch (Exception e) {	// 有可能mConnection都为空  
                    Log.e(LOGTAG, "LoginTask.run()... other error");
                    Log.e(LOGTAG, "Failed to login to xmpp server. Caused by: "
                            + e.getMessage());
                    xmppManager.startReconnectionThread();  //启动重连线程
                }

            } else {			//已经登陆
                Log.i(LOGTAG, "Logged in already");
                xmppManager.runTask();
            }

        }
    }

}
