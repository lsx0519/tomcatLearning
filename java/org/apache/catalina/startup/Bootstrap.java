/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
/**
 * 
* @ClassName: Bootstrap   
* @Description: TODO(Catalina.class 引导加载程序
* 这个应用程序构造了一个类加载器，用于加载Catalina内部类
* 通过"catalina.home"目录的jar包
* )   
* @author 范再军  
* @date 2018年3月30日 下午3:49:58   
*
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    //守护进程
    private static Bootstrap daemon = null;
    //存放"catalina.home" 
    //home属性主要包含:  bin(主要包含启动停止脚本), lib(公用jar包), endorsed(要覆盖JRE的jar).
    private static final File catalinaBaseFile;
    //存放"catalina.base"   
    //base属性主要包含 :  bin(只能包含setevn(设置JRE_HOME等环境变量),  tomcat-juli.jar(日志相关)文件), conf, logs, webapps, work, temp, 
    //lib(该tomcat实例下所有web应用共享的jar,tomcat加载时,会先加载这里的jar再加载home/lib下的jar).
    private static final File catalinaHomeFile;
    //replace方法里，做参数匹配
    private static final Pattern PATH_PATTERN = Pattern.compile("(\".*?\")|(([^,])*)");
   
    //静态区，主要设置catalina.home和catalina.base
    
    static {
        // 获取当前用户的当前工作目录 E:\IntellgintService\tomcat8-gitee
        String userDir = System.getProperty("user.dir");

        // 获取catalina的主目录  eg：launch jvm参数设置的-Dcatalina.home=E:/IntellgintService/tomcat8-gitee/launch
        String home = System.getProperty(Globals.CATALINA_HOME_PROP);
        File homeFile = null;
        //home部为null，创建此launch的File类
        if (home != null) {
            File f = new File(home);
            try {
            	//返回抽象路径名的规范路径名字符串:规范路径名是绝对路径名，并且是惟一的。规范路径名的准确定义与系统有关 eg:E:\IntellgintService\tomcat8-gitee\launch
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
            	//返回抽象路径名的绝对路径名字符串
                homeFile = f.getAbsoluteFile();
            }
        }
        //jvm 没有配置-Dcatalina.home=launch
        if (homeFile == null) {
            // 第一步:调用，安装tomcat时，不设置，会走到这里来
            // 在正常情况下安装  bootstrapJar D:\source\mvn-tomcat\tomcat8\bootstrap.jar
            File bootstrapJar = new File(userDir, "bootstrap.jar");
            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }
        //还是为空，就取取userDir的路径
        if (homeFile == null) {
            // 第二步 ,用userDir来建file获取homeFile
            File f = new File(userDir);
            try {
            	//返回抽象路径名的规范路径名字符串:规范路径名是绝对路径名，并且是惟一的。规范路径名的准确定义与系统有关 eg:D:\source\mvn-tomcat\tomcat8
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
            	//返回抽象路径名的绝对路径名字符串
                homeFile = f.getAbsoluteFile();
            }
        }
        //catalina的主目录空间
        catalinaHomeFile = homeFile;
        //设置系统属性："catalina.home"
        System.setProperty(
                Globals.CATALINA_HOME_PROP, catalinaHomeFile.getPath());
        //上面都是为了设置正确的"catalina.home"到系统里。
        // 获取"catalina.base" jvm设置参数 -Dcatalina.base=launch
        String base = System.getProperty(Globals.CATALINA_BASE_PROP);
        if (base == null) {
        	// jvm没有设置参数，就用系统中"catalina.home"的值
            catalinaBaseFile = catalinaHomeFile;
        } else {
        	// jvm有设置参数,建立baseFile存储到baseFile
            File baseFile = new File(base);
            try {
            	//返回抽象路径名的规范路径名字符串:规范路径名是绝对路径名，并且是惟一的。规范路径名的准确定义与系统有关 eg:E:\IntellgintService\tomcat8-gitee\launch
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
            	//返回抽象路径名的绝对路径名字符串
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        //设置系统属性："catalina.base"
        System.setProperty(
                Globals.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * tomcat容器启动类Catalina.org.apache.catalina.startup.Catalina
     */
    private Object catalinaDaemon = null;
    
    //三个加载器commonLoader，catalinaLoader，sharedLoader初始化
    protected ClassLoader commonLoader = null;
    protected ClassLoader catalinaLoader = null;
    protected ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods
    //tomcat的系统类装载器和JDK的系统类装载器有点不同的地方是搜索路径并不相同
    //catalina.bat 先将classpath清空，因为classpath中可能有tomcat启动相关的类会影响tomcat的正常启动。然后将bootstrap.jar和tomcat-juli.jar加入classpath中，在catalina.bat中调用了Bootstrap类的main方法，
    //tomcat这里系统类装载器会装载Bootstrap类，Bootstrap类用到的Catalina类也是由系统类装载器装载的。
    //随后在Bootstrap的init方法中创建了3个类装载器：
    private void initClassLoaders() {
        try {
        	//首先创建CommonLoader，在本类中createClassLoader方法完成
            commonLoader = createClassLoader("common", null);
            if( commonLoader == null ) {
                //如果没有额外的配置lib包，就用这个类默认的加载器，说明这是一个简单的环境
                commonLoader=this.getClass().getClassLoader();
            }
            //然后创建catalinaLoader和sharedLoader,没有就默认都是commonLoader
            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }
    
    /**
     * 
    * @Title: createClassLoader   
    * @Description: TODO(首先创建CommonLoader)   
    * @param name
    * @param parent
    * @return
    * @throws Exception  
    * ClassLoader  
    * @throws (没有异常抛出)
     */
    private ClassLoader createClassLoader(String name, ClassLoader parent)
        throws Exception {
    	//catalina.properties在CatalinaProperties读取
    	//获取入参的加载器名称 common.loader="${catalina.base}/lib","${catalina.base}/lib/*.jar","${catalina.home}/lib","${catalina.home}/lib/*.jar"
    	//catalina.properties的值：server.loader=
    	//catalina.properties的值：shared.loade=
        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals("")))
            return parent;
        //解析到绝对路径
        //common:"D:\source\mvn-tomcat\tomcat8\launch/lib","D:\source\mvn-tomcat\tomcat8\launch/lib/*.jar","D:\source\mvn-tomcat\tomcat8\launch/lib","D:\source\mvn-tomcat\tomcat8\launch/lib/*.jar"
        value = replace(value);
        //属性接收list：位置和类型
        List<Repository> repositories = new ArrayList<>();
        //属性路径数组 :
        //[D:\source\mvn-tomcat\tomcat8\launch/lib, D:\source\mvn-tomcat\tomcat8\launch/lib/*.jar, 
        //D:\source\mvn-tomcat\tomcat8\launch/lib, D:\source\mvn-tomcat\tomcat8\launch/lib/*.jar]
        String[] repositoryPaths = getPaths(value);
        //遍历这个路径
        for (String repository : repositoryPaths) {
            // 校验是否是一个jar包
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                //如果是URL格式，就存入url地址格式
                repositories.add(
                        new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            //放在本地就是以jar,dir,glob的形式
            if (repository.endsWith("*.jar")) {
                repository = repository.substring
                    (0, repository.length() - "*.jar".length());
                repositories.add(
                        new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(
                        new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(
                        new Repository(repository, RepositoryType.DIR));
            }
        }
        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     * @return the modified string
     */
    /**
     * 
    * @Title: replace   
    * @Description: TODO(${}解析，破规则)   
    * @param str
    * @return  
    * String  
    * @throws (没有异常抛出)
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * 
    * @Title: init   
    * @Description: TODO(初始化守护进程)   
    * @throws Exception  
    * void  
    * @throws (没有异常抛出)
     */
    public void init() throws Exception {
    	//初始化类加载器，commonLoader，catalinaLoader,sharedLoader
        initClassLoaders();
        //设置当前线程上下文类加载器为catalinaLoader
        //将catalinaLoader设置为Tomcat主线程的(当前)线程上下文类加载器；
        Thread.currentThread().setContextClassLoader(catalinaLoader);
        //线程安全的加载class，这里包含这些包路径的类，其中部分被实例化
		//loadCorePackage(loader);
		//loadCoyotePackage(loader);
		//loadLoaderPackage(loader);
		//loadRealmPackage(loader);
		//loadServletsPackage(loader);
		//loadSessionPackage(loader);
		//loadUtilPackage(loader);
		//loadValvesPackage(loader);
		//loadJavaxPackage(loader);
		//loadConnectorPackage(loader);
		//loadTomcatPackage(loader);
        // 如果当前有SecurityManager, 则提前加载一些类隐式加载，就是指在当前类中所有new的对象，如果没有被加载，则使用当前类的类加载器加载，即this.getClass(),getClassLoader()会默认加载该类中所有被new出来的对象的类（前提是他们没在别处先被加载过）。
        SecurityClassLoad.securityClassLoad(catalinaLoader);
        // 上面没有加载org.apache.catalina.startup.Catalina
        // 加载启动类 并调用启动类的 process()方法
        if (log.isDebugEnabled())
            log.debug("Loading startup class");
        Class<?> startupClass =
            catalinaLoader.loadClass
            ("org.apache.catalina.startup.Catalina");
        //建立启动类实例
        Object startupInstance = startupClass.newInstance();
        
        //Set the shared extensions class loader
        //设置共享扩展类加载器
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");
        //方法名称
        String methodName = "setParentClassLoader";
        //类加载类型参数
        Class<?> paramTypes[] = new Class[1];
        //类加载类型参数第一个参数ClassLoader
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        //参数值：sharedLoader
        paramValues[0] = sharedLoader;
        //利用反射，通过setParentClassLoader，java.lang.ClassLoader来寻找启动方法 
        //设置Catalina实例的parentClassLoader属性为sharedLoader类加载器.
        Method method =
            startupInstance.getClass().getMethod(methodName, paramTypes);
        //调用此方法，设置parentClassLoader=paramTypes[0] 为容器下webapp的webappClassLoader的parent
        method.invoke(startupInstance, paramValues);
        //设置tomcat启动类org.apache.catalina.startup.Catalina@43a25848
        catalinaDaemon = startupInstance;

    }
    /**
     * 
    * @Title: load   
    * @Description: TODO(去调用catalina的load方法)   
    * @param arguments
    * @throws Exception  
    * void  
    * @throws (没有异常抛出)
     */
    private void load(String[] arguments)
        throws Exception {
        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        // Catalina load方法调用
        Method method =
            catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled())
            log.debug("Calling startup class " + method);
        method.invoke(catalinaDaemon, param);

    }

    
    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method =
            catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);

    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     */
    public void init(String[] arguments)
        throws Exception {

        init();
        load(arguments);

    }


    /**
     * Start the Catalina daemon.
     */
    public void start()
        throws Exception {
        if( catalinaDaemon==null ) init();

        Method method = catalinaDaemon.getClass().getMethod("start", (Class [] )null);
        method.invoke(catalinaDaemon, (Object [])null);

    }


    /**
     * Stop the Catalina Daemon.
     */
    public void stop()
        throws Exception {

        Method method = catalinaDaemon.getClass().getMethod("stop", (Class [] ) null);
        method.invoke(catalinaDaemon, (Object [] ) null);

    }


    /**
     * Stop the standalone server.
     */
    public void stopServer()
        throws Exception {

        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);

    }


   /**
     * Stop the standalone server.
     */
    public void stopServer(String[] arguments)
        throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);

    }


    /**
     * 
    * @Title: setAwait   
    * @Description: TODO(
    * setAwait的方法的设置使得Catalina 在启动Server的时候，
    * Server一直在一个循环中执行wait操作等待请求的到来，
    * load则是加载tomcat启动必须的数据（例如配置文件等等），
    * 最后start 方法则是正真调用Server的启动方法)   
    * @param await
    * @throws Exception  
    * void  
    * @throws (没有异常抛出)
     */
    public void setAwait(boolean await)
        throws Exception {
    	//利用反射调用catalina的setAwait方法
        Class<?> paramTypes[] = new Class[1];
        //不能实例化 Boolean.TYPE这行代码就是利用泛型把Void类与void关键字关联起来，是为了表示Void是void一个占位符的作用。内部的Class.getPrimitiveClass("boolean")是native
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
            catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);

    }
    /**
     * 
    * @Title: getAwait   
    * @Description: TODO(利用反射获取catalina的await的状态)   
    * @return
    * @throws Exception  
    * boolean  
    * @throws (没有异常抛出)
     */
    public boolean getAwait()
        throws Exception
    {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
            catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b=(Boolean)method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {
    	//初始化，获得加载器
        if (daemon == null) {
            // Don't set daemon until init() has completed
        	// 初始化完成之前不要设置守护进程
            Bootstrap bootstrap = new Bootstrap();
            try {
            	//执行初始化方法，
                bootstrap.init();
            } catch (Throwable t) {
                handleThrowable(t);
                t.printStackTrace();
                return;
            }
            //设置daemon为新创建的实例.
            daemon = bootstrap;
        } else {
            // When running as a service the call to stop will be on a new
            // thread so make sure the correct class loader is used to prevent
            // a range of class not found exceptions.
        	// 停止服务
            Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
        }
        // start 参数
        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                //加载参数,命令截取
                daemon.load(args);
                //启动
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
            	//设置server等待请求状态，为开，关做准备 , 阻塞main线程
                daemon.setAwait(true);
                //加载参数
                //加载Server
                daemon.load(args);
                //启动Server
                daemon.start();
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null==daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }

    }


    /**
     * 
    * @Title: getCatalinaHome   
    * @Description: TODO(提供获取"catalina.home"的路径)   
    * @return  
    * String  
    * @throws (没有异常抛出)
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * 
    * @Title: getCatalinaBase   
    * @Description: TODO(提供获取"catalina.base"的路径)   
    * @return  
    * String  
    * @throws (没有异常抛出)
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * 
    * @Title: getCatalinaHomeFile   
    * @Description: TODO(提供获取"catalina.home"的File对象)   
    * @return  
    * File  
    * @throws (没有异常抛出)
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * 
    * @Title: getCatalinaHomeFile   
    * @Description: TODO(提供获取"catalina.base"的File对象)   
    * @return  
    * File  
    * @throws (没有异常抛出)
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // 复制ExceptionUtils的异常方法
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // 所有实现Throwable的实例都会被吞噬
    }


    //单元测试，破方法
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();
            if (path.length() == 0) {
                continue;
            }

            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);

            if (first == '"' && last == '"' && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
                if (path.length() == 0) {
                    continue;
                }
            } else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                throw new IllegalArgumentException(
                        "The double quote [\"] character only be used to quote paths. It must " +
                        "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }

            result.add(path);
        }
        return result.toArray(new String[result.size()]);
    }
}
