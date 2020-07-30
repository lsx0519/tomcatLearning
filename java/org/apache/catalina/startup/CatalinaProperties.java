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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * Utility class to read the bootstrap Catalina configuration.
 *
 * @author Remy Maucherat
 */
/**
 * 
* @ClassName: CatalinaProperties   
* @Description: TODO(Catalina配置获取)   
* @author 范再军  
* @date 2018年3月29日 下午6:27:09   
*
 */
public class CatalinaProperties {
	//日志记录
    private static final Log log = LogFactory.getLog(CatalinaProperties.class);
    //属性对象初始化
    private static Properties properties = null;

    /*
     * 静态初始化，都一次调用加载
     */
    static {
    	//加载容器中的配置
        loadProperties();
    }


    /**
     * 
    * @Title: getProperty   
    * @Description: TODO(提供获取本类中的properties的属性值)   
    * @param name
    * @return  
    * String  
    * @throws (没有异常抛出)
     */
    public static String getProperty(String name) {
        return properties.getProperty(name);
    }


    /**
     * 
    * @Title: loadProperties   
    * @Description: TODO(catalina属性加载到系统环境变量)     
    * void  
    * @throws (没有异常抛出)
     */
    private static void loadProperties() {
    	//输入流定义，获取配置信息用
        InputStream is = null;
        //异常声明
        Throwable error = null;
        //第一步：
        try {
        	//第一步:读取系统的catalina.config的值，默认是null
            String configUrl = System.getProperty("catalina.config");
            //如果此时存在这个值，就把这个作为字符串作为URL流读入到is中
            if (configUrl != null) {
                is = (new URL(configUrl)).openStream();
            }
        } catch (Throwable t) {
        	//异常处理
            handleThrowable(t);
        }
        //第二步：conf:catalina.properties
        if (is == null) {
            try {
            	//获取catalina主目录
                File home = new File(Bootstrap.getCatalinaBase());
                //catalina主目录下有一个conf文件
                //conf:D:\source\mvn-tomcat\tomcat8\launch\conf
                File conf = new File(home, "conf");
                //获取该文件夹下面的catalina.properties
                File propsFile = new File(conf, "catalina.properties");
                is = new FileInputStream(propsFile);
            } catch (Throwable t) {
                handleThrowable(t);
            }
        }
       //第三步：/org/apache/catalina/startup/catalina.properties
        if (is == null) {
            try {
                is = CatalinaProperties.class.getResourceAsStream
                    ("/org/apache/catalina/startup/catalina.properties");
            } catch (Throwable t) {
                handleThrowable(t);
            }
        }
        //第四步：is流解析读取属性到properties
        if (is != null) {
            try {
                properties = new Properties();
                properties.load(is);
            } catch (Throwable t) {
                handleThrowable(t);
                error = t;
            } finally {
                try {
                    is.close();
                } catch (IOException ioe) {
                    log.warn("Could not close catalina.properties", ioe);
                }
            }
        }
        //如果以上获取catalina属性都不成功，则清空properties
        if ((is == null) || (error != null)) {
            // 打印日志
            log.warn("Failed to load catalina.properties", error);
            // 重置
            properties = new Properties();
        }

        // 将上面属性注册到系统环境里
        // 迭代器遍历
        Enumeration<?> enumeration = properties.propertyNames();
        while (enumeration.hasMoreElements()) {
            String name = (String) enumeration.nextElement();
            String value = properties.getProperty(name);
            if (value != null) {
            	//存到系统环境
                System.setProperty(name, value);
            }
        }
    }


    /**
     * 
    * @Title: handleThrowable   
    * @Description: TODO(此代码从ExceptionUtils类中复制来的，解释略，异常处理)   
    * @param t  
    * void  
    * @throws (没有异常抛出)
     */
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        //所有其他hrowable的实例都会被吞噬
    }
}
