package org.hzero.admin.app.service.impl;

import com.alibaba.fastjson.JSON;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.hzero.admin.api.dto.TraceReport;
import org.hzero.admin.app.service.TraceAnalysisService;
import org.hzero.core.redis.DynamicRedisHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * @author XCXCXCXCX
 * @version 1.2.0
 * @date 2019/12/23 3:56 下午
 */
@Service
public class TraceAnalysisServiceImpl implements TraceAnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceAnalysisServiceImpl.class);

    private static final int CURRENT_REDIS_DB = 1;
    private static final String TRACE_GROUP_ID_KEY_PREFIX = "hadm:trace-group-id:";
    public static final String TRACE_GROUP_KEY_PREFIX = "hadm:trace-group:";

    private static final String DATASOURCE_TYPE = "dataSource";
    private static final String SPRING_MVC_TYPE = "springmvc";
    private static final String FEIGN_TYPE = "open-feign";

    @Autowired
    private DynamicRedisHelper redisHelper;

    @Override
    public void start() {
        CustomUserDetails customUserDetails = DetailsHelper.getUserDetails();
        if (customUserDetails == null) {
            throw new CommonException("hadm.error.user_detail_not_found");
        }
        Long tenantId = customUserDetails.getTenantId();
        Long userId = customUserDetails.getUserId();
        //{租户ID}:{用户ID}
        String traceGroupIdKey = tenantId + ":" + userId;
        try {
            redisHelper.setCurrentDatabase(CURRENT_REDIS_DB);
            boolean flag = redisHelper.strSetIfAbsent(TRACE_GROUP_ID_KEY_PREFIX + traceGroupIdKey, generateTraceGroupId(traceGroupIdKey));
            if (!flag) {
                throw new CommonException("hadm.error.trace_is_already_enabled");
            }
        } finally {
            redisHelper.clearCurrentDatabase();
        }
    }

    @Override
    public boolean ifStarted() {
        CustomUserDetails customUserDetails = DetailsHelper.getUserDetails();
        if (customUserDetails == null) {
            throw new CommonException("hadm.error.user_detail_not_found");
        }
        Long tenantId = customUserDetails.getTenantId();
        Long userId = customUserDetails.getUserId();
        //{租户ID}:{用户ID}
        String traceGroupIdKey = tenantId + ":" + userId;
        try {
            redisHelper.setCurrentDatabase(CURRENT_REDIS_DB);
            String traceGroupId = redisHelper.strGet(TRACE_GROUP_ID_KEY_PREFIX + traceGroupIdKey);
            return traceGroupId != null;
        } finally {
            redisHelper.clearCurrentDatabase();
        }
    }

    private String generateTraceGroupId(String traceGroupIdKey) {
        return traceGroupIdKey + ":" + System.currentTimeMillis();
    }

    @Override
    public TraceReport end() {
        CustomUserDetails customUserDetails = DetailsHelper.getUserDetails();
        if (customUserDetails == null) {
            throw new CommonException("hadm.error.user_detail_not_found");
        }
        Long tenantId = customUserDetails.getTenantId();
        Long userId = customUserDetails.getUserId();
        //{租户ID}:{用户ID}
        List<String> logList;
        String traceGroupId = null;
        try {
            redisHelper.setCurrentDatabase(CURRENT_REDIS_DB);
            String traceGroupIdKey = TRACE_GROUP_ID_KEY_PREFIX + tenantId + ":" + userId;
            traceGroupId = redisHelper.strGet(traceGroupIdKey);
            if (StringUtils.isEmpty(traceGroupId)) {
                throw new CommonException("hadm.error.trace_is_not_enabled");
            }
            String listKey = TRACE_GROUP_KEY_PREFIX + traceGroupId;
            logList = redisHelper.lstAll(listKey);
            redisHelper.delKey(traceGroupIdKey);
            redisHelper.setExpire(listKey, 300);
        } finally {
            redisHelper.clearCurrentDatabase();
        }
        return analysis(traceGroupId, logList);
    }

    public static TraceReport analysis(String traceGroupId, List<String> logList){
        TraceReport traceReport = new TraceReport(traceGroupId);
        if (CollectionUtils.isNotEmpty(logList)){
            for (String log : logList) {
                Map<String, Object> logJson = JSON.parseObject(log, Map.class);
                TraceReport.Trace trace = resolve(logJson);
                traceReport.addTrace(trace);
            }
        }
        return traceReport;
    }

    private static TraceReport.Trace resolve(Map<String, Object> logJson) {
        String traceType = String.valueOf(logJson.get("tracer.type"));
        Long timestamp = (Long) logJson.get("timestamp");
        String cost = String.valueOf(logJson.get("cost"));
        String resultCode = String.valueOf(logJson.get("result.code"));
        String appName = String.valueOf(logJson.get("local.app"));
        String threadName = String.valueOf(logJson.get("current.thread.name"));
        String traceGroupId = String.valueOf(logJson.get("traceGroupId"));
        String traceId = String.valueOf(logJson.get("traceId"));
        String spanId = String.valueOf(logJson.get("spanId"));
        String bizInfo = String.valueOf(logJson.get("biz.baggage"));
        String sysInfo = String.valueOf(logJson.get("sys.baggage"));

        TraceReport.Trace trace = new TraceReport.Trace();
        trace.setTimestamp(timestamp);
        trace.setCost(cost);
        trace.setResultCode(resultCode);
        trace.setAppName(appName);
        trace.setThreadName(threadName);
        trace.setTraceGroupId(traceGroupId);
        trace.setTraceId(traceId);
        trace.setSpanId(spanId);
        trace.setBusinessAdditionInfo(bizInfo);
        trace.setSystemAdditionInfo(sysInfo);
        if (DATASOURCE_TYPE.equals(traceType)) {
            TraceReport.DataSourceTrace dataSourceTrace = new TraceReport.DataSourceTrace();
            BeanUtils.copyProperties(trace, dataSourceTrace);
            dataSourceTrace.setTraceType("SQL");
            dataSourceTrace.setDbType(String.valueOf(logJson.get("database.type")));
            dataSourceTrace.setDbEndpoint(String.valueOf(logJson.get("database.endpoint")));
            dataSourceTrace.setDbName(String.valueOf(logJson.get("database.name")));
            dataSourceTrace.setSql(String.valueOf(logJson.get("sql")));
            return dataSourceTrace;
        } else if (SPRING_MVC_TYPE.equals(traceType)) {
            TraceReport.HttpTrace httpTrace = new TraceReport.HttpTrace();
            BeanUtils.copyProperties(trace, httpTrace);
            httpTrace.setTraceType("HTTP");
            httpTrace.setRequestUrl(String.valueOf(logJson.get("request.url")));
            httpTrace.setMethod(String.valueOf(logJson.get("method")));
            httpTrace.setRequestSizeBytes((Integer) logJson.get("req.size.bytes"));
            httpTrace.setResponseSizeBytes((Integer) logJson.get("resp.size.bytes"));
            return httpTrace;
        } else if (FEIGN_TYPE.equals(traceType)) {
            TraceReport.FeignTrace feignTrace = new TraceReport.FeignTrace();
            BeanUtils.copyProperties(trace, feignTrace);
            feignTrace.setTraceType("FEIGN");
            feignTrace.setRequestUrl(String.valueOf(logJson.get("request.url")));
            feignTrace.setMethod(String.valueOf(logJson.get("method")));
            feignTrace.setRequestSizeBytes((Integer) logJson.get("req.size.bytes"));
            feignTrace.setResponseSizeBytes((Integer) logJson.get("resp.size.bytes"));
            feignTrace.setRemoteHost(String.valueOf(logJson.get("remote.host")));
            feignTrace.setRemotePort(String.valueOf(logJson.get("remote.port")));
            return feignTrace;
        }
        LOGGER.error("unknown trace.type[{}]", traceType);
        return trace;
    }

}
