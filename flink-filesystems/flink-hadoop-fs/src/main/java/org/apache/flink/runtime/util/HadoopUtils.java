/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.util;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.Preconditions;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.CallerContext;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;

/**
 * Utility class for working with Hadoop-related classes. This should only be used if Hadoop is on
 * the classpath.
 */
public class HadoopUtils {

    private static final Logger LOG = LoggerFactory.getLogger(HadoopUtils.class);

    static final Text HDFS_DELEGATION_TOKEN_KIND = new Text("HDFS_DELEGATION_TOKEN");

    /** The prefixes that Flink adds to the Hadoop config. */
    private static final String[] FLINK_CONFIG_PREFIXES = {"flink.hadoop."};

    @SuppressWarnings("deprecation")
    public static Configuration getHadoopConfiguration(
            org.apache.flink.configuration.Configuration flinkConfiguration) {

        // Instantiate an HdfsConfiguration to load the hdfs-site.xml and hdfs-default.xml
        // from the classpath

        Configuration result = new HdfsConfiguration();
        boolean foundHadoopConfiguration = false;

        // We need to load both core-site.xml and hdfs-site.xml to determine the default fs path and
        // the hdfs configuration.
        // The properties of a newly added resource will override the ones in previous resources, so
        // a configuration
        // file with higher priority should be added later.

        // Approach 1: HADOOP_HOME environment variables
        String[] possibleHadoopConfPaths = new String[2];

        final String hadoopHome = System.getenv("HADOOP_HOME");
        if (hadoopHome != null) {
            LOG.debug("Searching Hadoop configuration files in HADOOP_HOME: {}", hadoopHome);
            possibleHadoopConfPaths[0] = hadoopHome + "/conf";
            possibleHadoopConfPaths[1] = hadoopHome + "/etc/hadoop"; // hadoop 2.2
        }

        for (String possibleHadoopConfPath : possibleHadoopConfPaths) {
            if (possibleHadoopConfPath != null) {
                foundHadoopConfiguration = addHadoopConfIfFound(result, possibleHadoopConfPath);
            }
        }

        // Approach 2: Flink configuration (deprecated)
        final String hdfsDefaultPath =
                flinkConfiguration.getString(ConfigConstants.HDFS_DEFAULT_CONFIG, null);
        if (hdfsDefaultPath != null) {
            result.addResource(new org.apache.hadoop.fs.Path(hdfsDefaultPath));
            LOG.debug(
                    "Using hdfs-default configuration-file path from Flink config: {}",
                    hdfsDefaultPath);
            foundHadoopConfiguration = true;
        }

        final String hdfsSitePath =
                flinkConfiguration.getString(ConfigConstants.HDFS_SITE_CONFIG, null);
        if (hdfsSitePath != null) {
            result.addResource(new org.apache.hadoop.fs.Path(hdfsSitePath));
            LOG.debug(
                    "Using hdfs-site configuration-file path from Flink config: {}", hdfsSitePath);
            foundHadoopConfiguration = true;
        }

        final String hadoopConfigPath =
                flinkConfiguration.getString(ConfigConstants.PATH_HADOOP_CONFIG, null);
        if (hadoopConfigPath != null) {
            LOG.debug("Searching Hadoop configuration files in Flink config: {}", hadoopConfigPath);
            foundHadoopConfiguration =
                    addHadoopConfIfFound(result, hadoopConfigPath) || foundHadoopConfiguration;
        }

        // Approach 3: HADOOP_CONF_DIR environment variable
        String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");
        if (hadoopConfDir != null) {
            LOG.debug("Searching Hadoop configuration files in HADOOP_CONF_DIR: {}", hadoopConfDir);
            foundHadoopConfiguration =
                    addHadoopConfIfFound(result, hadoopConfDir) || foundHadoopConfiguration;
        }

        // Approach 4: Flink configuration
        // add all configuration key with prefix 'flink.hadoop.' in flink conf to hadoop conf
        for (String key : flinkConfiguration.keySet()) {
            for (String prefix : FLINK_CONFIG_PREFIXES) {
                if (key.startsWith(prefix)) {
                    String newKey = key.substring(prefix.length());
                    String value = flinkConfiguration.getString(key, null);
                    result.set(newKey, value);
                    LOG.debug(
                            "Adding Flink config entry for {} as {}={} to Hadoop config",
                            key,
                            newKey,
                            value);
                    foundHadoopConfiguration = true;
                }
            }
        }

        if (!foundHadoopConfiguration) {
            LOG.warn(
                    "Could not find Hadoop configuration via any of the supported methods "
                            + "(Flink configuration, environment variables).");
        }

        return result;
    }

    public static boolean isKerberosSecurityEnabled(UserGroupInformation ugi) {
        return UserGroupInformation.isSecurityEnabled()
                && ugi.getAuthenticationMethod()
                        == UserGroupInformation.AuthenticationMethod.KERBEROS;
    }

    public static boolean areKerberosCredentialsValid(
            UserGroupInformation ugi, boolean useTicketCache) {
        Preconditions.checkState(isKerberosSecurityEnabled(ugi));

        // note: UGI::hasKerberosCredentials inaccurately reports false
        // for logins based on a keytab (fixed in Hadoop 2.6.1, see HADOOP-10786),
        // so we check only in ticket cache scenario.
        if (useTicketCache && !ugi.hasKerberosCredentials()) {
            if (hasHDFSDelegationToken(ugi)) {
                LOG.warn(
                        "Hadoop security is enabled but current login user does not have Kerberos credentials, "
                                + "use delegation token instead. Flink application will terminate after token expires.");
                return true;
            } else {
                LOG.error(
                        "Hadoop security is enabled, but current login user has neither Kerberos credentials "
                                + "nor delegation tokens!");
                return false;
            }
        }

        return true;
    }

    /** Indicates whether the user has an HDFS delegation token. */
    public static boolean hasHDFSDelegationToken(UserGroupInformation ugi) {
        Collection<Token<? extends TokenIdentifier>> usrTok = ugi.getTokens();
        for (Token<? extends TokenIdentifier> token : usrTok) {
            if (token.getKind().equals(HDFS_DELEGATION_TOKEN_KIND)) {
                return true;
            }
        }
        return false;
    }

    /** Checks if the Hadoop dependency is at least the given version. */
    public static boolean isMinHadoopVersion(int major, int minor) throws FlinkRuntimeException {
        final Tuple2<Integer, Integer> hadoopVersion = getMajorMinorBundledHadoopVersion();
        int maj = hadoopVersion.f0;
        int min = hadoopVersion.f1;

        return maj > major || (maj == major && min >= minor);
    }

    /** Checks if the Hadoop dependency is at most the given version. */
    public static boolean isMaxHadoopVersion(int major, int minor) throws FlinkRuntimeException {
        final Tuple2<Integer, Integer> hadoopVersion = getMajorMinorBundledHadoopVersion();
        int maj = hadoopVersion.f0;
        int min = hadoopVersion.f1;

        return maj < major || (maj == major && min < minor);
    }

    private static Tuple2<Integer, Integer> getMajorMinorBundledHadoopVersion() {
        String versionString = VersionInfo.getVersion();
        String[] versionParts = versionString.split("\\.");

        if (versionParts.length < 2) {
            throw new FlinkRuntimeException(
                    "Cannot determine version of Hadoop, unexpected version string: "
                            + versionString);
        }

        int maj = Integer.parseInt(versionParts[0]);
        int min = Integer.parseInt(versionParts[1]);
        return Tuple2.of(maj, min);
    }

    /**
     * Search Hadoop configuration files in the given path, and add them to the configuration if
     * found.
     */
    private static boolean addHadoopConfIfFound(
            Configuration configuration, String possibleHadoopConfPath) {
        boolean foundHadoopConfiguration = false;
        if (new File(possibleHadoopConfPath).exists()) {
            if (new File(possibleHadoopConfPath + "/core-site.xml").exists()) {
                configuration.addResource(
                        new org.apache.hadoop.fs.Path(possibleHadoopConfPath + "/core-site.xml"));
                LOG.debug(
                        "Adding "
                                + possibleHadoopConfPath
                                + "/core-site.xml to hadoop configuration");
                foundHadoopConfiguration = true;
            }
            if (new File(possibleHadoopConfPath + "/hdfs-site.xml").exists()) {
                configuration.addResource(
                        new org.apache.hadoop.fs.Path(possibleHadoopConfPath + "/hdfs-site.xml"));
                LOG.debug(
                        "Adding "
                                + possibleHadoopConfPath
                                + "/hdfs-site.xml to hadoop configuration");
                foundHadoopConfiguration = true;
            }
        }
        return foundHadoopConfiguration;
    }

    /**
     * Set up the caller context [[callerContext]] by invoking Hadoop CallerContext API of
     * [[org.apache.hadoop.ipc.CallerContext]], which was added in hadoop 2.8.
     */
    public static void setCallerContext(
            String callerContext, org.apache.flink.configuration.Configuration flinkConfig) {
        try {
            callerContext = truncateCallerContext(callerContext, flinkConfig);
            CallerContext.setCurrent(new CallerContext.Builder(callerContext).build());
        } catch (Exception e) {
            LOG.warn("Not supported CallerContext with exception: ", e);
        }
    }

    /** Truncate callerContext for demand. */
    private static String truncateCallerContext(
            String callerContext, org.apache.flink.configuration.Configuration conf) {
        // The default max size of Hadoop caller context is 128
        int len = getHadoopConfiguration(conf).getInt("hadoop.caller.context.max.size", 128);
        if (callerContext == null || callerContext.length() <= len) {
            return callerContext;
        } else {
            String finalCallerContext = callerContext.substring(0, len);
            LOG.warn(
                    "Truncated Flink caller context from "
                            + callerContext
                            + " to "
                            + finalCallerContext);
            return finalCallerContext;
        }
    }
}
