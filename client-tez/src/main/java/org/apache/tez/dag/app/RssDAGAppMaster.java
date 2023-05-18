package org.apache.tez.dag.app;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.yarn.YarnUncaughtExceptionHandler;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.tez.common.TezCommonUtils;
import org.apache.tez.common.TezUtilsInternal;
import org.apache.tez.common.VersionInfo;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezConstants;
import org.apache.tez.dag.api.records.DAGProtos;
import org.apache.tez.dag.app.dag.impl.DAGImpl;
import org.apache.tez.dag.app.dag.impl.RssDAGImplV2;
import org.apache.tez.dag.common.rss.RssTezUtils;
import org.apache.tez.dag.records.TezDAGID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class RssDAGAppMaster extends DAGAppMaster {

  private static final Logger LOG = LoggerFactory.getLogger(DAGAppMaster.class);

  public RssDAGAppMaster(ApplicationAttemptId applicationAttemptId, ContainerId containerId, String nmHost,
                         int nmPort, int nmHttpPort, Clock clock, long appSubmitTime, boolean isSession,
                         String workingDirectory, String[] localDirs, String[] logDirs, String clientVersion,
                         Credentials credentials, String jobUserName,
                         DAGProtos.AMPluginDescriptorProto pluginDescriptorProto) {
    super(applicationAttemptId, containerId, nmHost, nmPort, nmHttpPort, clock, appSubmitTime, isSession,
      workingDirectory, localDirs, logDirs, clientVersion, credentials, jobUserName, pluginDescriptorProto);
  }

  @Override
  public synchronized void serviceInit(Configuration conf) throws Exception {
    // There are no container to share between attempts. Rss don't support shared container between attempts yet.
    if (conf.getBoolean(TezConfiguration.DAG_RECOVERY_ENABLED, TezConfiguration.DAG_RECOVERY_ENABLED_DEFAULT)) {
      conf.getBoolean(TezConfiguration.DAG_RECOVERY_ENABLED, false);
      LOG.warn("close recovery enable, because RSS doesn't support it yet");
    }
    super.serviceInit(conf);
  }

  private static void validateInputParam(String value, String param)
    throws IOException {
    if (value == null) {
      String msg = param + " is null";
      LOG.error(msg);
      throw new IOException(msg);
    }
  }

  DAGImpl createDAG(DAGProtos.DAGPlan dagPB, TezDAGID dagId) {
    DAGImpl dag = super.createDAG(dagPB, dagId);
    // We can't get ApplicationAttemptId from TezTaskContextImpl, so we use ApplicationId + attemptId
    return new RssDAGImplV2(dag, super.getContext().getAMConf(),
        RssTezUtils.constructAppId(this.getAppID(), this.getAttemptID().getAttemptId()));
  }

  public static void main(String[] args) {
    try {
      Thread.setDefaultUncaughtExceptionHandler(new YarnUncaughtExceptionHandler());
      final String pid = System.getenv().get("JVM_PID");
      String containerIdStr =
        System.getenv(ApplicationConstants.Environment.CONTAINER_ID.name());
      String nodeHostString = System.getenv(ApplicationConstants.Environment.NM_HOST.name());
      String nodePortString = System.getenv(ApplicationConstants.Environment.NM_PORT.name());
      String nodeHttpPortString =
        System.getenv(ApplicationConstants.Environment.NM_HTTP_PORT.name());
      String appSubmitTimeStr =
        System.getenv(ApplicationConstants.APP_SUBMIT_TIME_ENV);
      String clientVersion = System.getenv(TezConstants.TEZ_CLIENT_VERSION_ENV);
      if (clientVersion == null) {
        clientVersion = VersionInfo.UNKNOWN;
      }

      validateInputParam(appSubmitTimeStr,
        ApplicationConstants.APP_SUBMIT_TIME_ENV);

      ContainerId containerId = ConverterUtils.toContainerId(containerIdStr);
      ApplicationAttemptId applicationAttemptId =
        containerId.getApplicationAttemptId();

      long appSubmitTime = Long.parseLong(appSubmitTimeStr);

      String jobUserName = System
        .getenv(ApplicationConstants.Environment.USER.name());

      // Command line options
      Options opts = new Options();
      opts.addOption(TezConstants.TEZ_SESSION_MODE_CLI_OPTION,
        false, "Run Tez Application Master in Session mode");

      CommandLine cliParser = new GnuParser().parse(opts, args);
      boolean sessionModeCliOption = cliParser.hasOption(TezConstants.TEZ_SESSION_MODE_CLI_OPTION);

      LOG.info("Creating DAGAppMaster for "
        + "applicationId=" + applicationAttemptId.getApplicationId()
        + ", attemptNum=" + applicationAttemptId.getAttemptId()
        + ", AMContainerId=" + containerId
        + ", jvmPid=" + pid
        + ", userFromEnv=" + jobUserName
        + ", cliSessionOption=" + sessionModeCliOption
        + ", pwd=" + System.getenv(ApplicationConstants.Environment.PWD.name())
        + ", localDirs=" + System.getenv(ApplicationConstants.Environment.LOCAL_DIRS.name())
        + ", logDirs=" + System.getenv(ApplicationConstants.Environment.LOG_DIRS.name()));

      // TODO Does this really need to be a YarnConfiguration ?
      Configuration conf = new Configuration(new YarnConfiguration());

      DAGProtos.ConfigurationProto confProto =
        TezUtilsInternal.readUserSpecifiedTezConfiguration(System.getenv(ApplicationConstants.Environment.PWD.name()));
      TezUtilsInternal.addUserSpecifiedTezConfiguration(conf, confProto.getConfKeyValuesList());

      DAGProtos.AMPluginDescriptorProto amPluginDescriptorProto = null;
      if (confProto.hasAmPluginDescriptor()) {
        amPluginDescriptorProto = confProto.getAmPluginDescriptor();
      }

      UserGroupInformation.setConfiguration(conf);
      Credentials credentials = UserGroupInformation.getCurrentUser().getCredentials();

      TezUtilsInternal.setSecurityUtilConfigration(LOG, conf);

      DAGAppMaster appMaster =
        new DAGAppMaster(applicationAttemptId, containerId, nodeHostString,
          Integer.parseInt(nodePortString),
          Integer.parseInt(nodeHttpPortString), new SystemClock(), appSubmitTime,
          sessionModeCliOption,
          System.getenv(ApplicationConstants.Environment.PWD.name()),
          TezCommonUtils.getTrimmedStrings(System.getenv(ApplicationConstants.Environment.LOCAL_DIRS.name())),
          TezCommonUtils.getTrimmedStrings(System.getenv(ApplicationConstants.Environment.LOG_DIRS.name())),
          clientVersion, credentials, jobUserName, amPluginDescriptorProto);
      ShutdownHookManager.get().addShutdownHook(
        new DAGAppMasterShutdownHook(appMaster), SHUTDOWN_HOOK_PRIORITY);

      // log the system properties
      if (LOG.isInfoEnabled()) {
        String systemPropsToLog = TezCommonUtils.getSystemPropertiesToLog(conf);
        if (systemPropsToLog != null) {
          LOG.info(systemPropsToLog);
        }
      }

      initAndStartAppMaster(appMaster, conf);

    } catch (Throwable t) {
      LOG.error("Error starting DAGAppMaster", t);
      System.exit(1);
    }
  }
}
