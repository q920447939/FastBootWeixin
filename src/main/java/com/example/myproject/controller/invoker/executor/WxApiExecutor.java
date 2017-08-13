package com.example.myproject.controller.invoker.executor;

import com.example.myproject.controller.invoker.WxApiMethodInfo;
import com.example.myproject.controller.invoker.annotation.WxApiBody;
import com.example.myproject.controller.invoker.annotation.WxApiForm;
import com.example.myproject.controller.invoker.annotation.WxApiRequest;
import com.example.myproject.controller.invoker.common.ReaderInputStream;
import com.example.myproject.exception.WxApiResponseException;
import com.example.myproject.exception.WxApiResultException;
import com.example.myproject.exception.WxAppException;
import com.example.myproject.mvc.WxRequestUtils;
import com.example.myproject.support.AccessTokenManager;
import com.example.myproject.util.WxAppAssert;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;

/**
 * FastBootWeixin  WxApiInvokeService
 * 注意拦截调用异常，如果是token过期，重新获取token并重试
 *
 * @author Guangshan
 * @summary FastBootWeixin  WxApiInvokeService
 * @Copyright (c) 2017, Guangshan Group All Rights Reserved
 * @since 2017/7/23 17:14
 */
public class WxApiExecutor {

    private static final Log logger = LogFactory.getLog(MethodHandles.lookup().lookupClass());

    private static final String WX_ACCESS_TOKEN_PARAM_NAME = "access_token";

    private final WxApiInvoker wxApiInvoker;

    private final AccessTokenManager accessTokenManager;

    private final WxApiResponseExtractor wxApiResponseExtractor;

    private final ObjectMapper jsonConverter = new ObjectMapper();

    private final ConversionService conversionService;

    public WxApiExecutor(WxApiInvoker wxApiInvoker, AccessTokenManager accessTokenManager, ConversionService conversionService) {
        this.wxApiInvoker = wxApiInvoker;
        this.wxApiResponseExtractor = new WxApiResponseExtractor(this.wxApiInvoker.getMessageConverters());
        this.accessTokenManager = accessTokenManager;
        this.conversionService = conversionService;
    }

    public Object execute(WxApiMethodInfo wxApiMethodInfo, Object[] args) {
        RequestEntity requestEntity = buildHttpRequestEntity(wxApiMethodInfo, args);
        // 后续这里可以区分情况，只有对于stream类型才使用extract，因为如果先执行转为HttpInputMessage
        // 其实是转为了byte放在了内存中，相当于多转了一层，大文件还是会多耗费点内存的，但是这里为了用更多的技术，就这样玩了。
        ResponseEntity<HttpInputMessage> responseEntity = wxApiInvoker.exchange(requestEntity, HttpInputMessage.class);
        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw new WxApiResponseException(responseEntity);
        }
        return wxApiResponseExtractor.extractData(responseEntity, wxApiMethodInfo.getReturnType());
    }

    private RequestEntity buildHttpRequestEntity(WxApiMethodInfo wxApiMethodInfo, Object[] args) {
        UriComponentsBuilder builder = wxApiMethodInfo.fromArgs(args);
        // 替换accessToken
        builder.replaceQueryParam(WX_ACCESS_TOKEN_PARAM_NAME, accessTokenManager.getToken());
        HttpHeaders httpHeaders = null;
        Object body = null;
        if (wxApiMethodInfo.getRequestMethod() == WxApiRequest.Method.JSON) {
            httpHeaders = buildJsonHeaders();
            body = getStringBody(wxApiMethodInfo, args);
        } else if (wxApiMethodInfo.getRequestMethod() == WxApiRequest.Method.XML) {
            httpHeaders = buildXmlHeaders();
            // 暂时不支持xml转换。。。
            body = getObjectBody(wxApiMethodInfo, args);
//            body = getStringBody(wxApiMethodInfo, args);
        } else if (wxApiMethodInfo.getRequestMethod() == WxApiRequest.Method.FORM) {
            body = getFormBody(wxApiMethodInfo, args);
        }
        return new RequestEntity(body, httpHeaders, wxApiMethodInfo.getRequestMethod().getHttpMethod(), builder.build().toUri());
    }

    private Object getObjectBody(WxApiMethodInfo wxApiMethodInfo, Object[] args) {
        MethodParameter methodParameter = wxApiMethodInfo.getMethodParameters().stream()
                .filter(p -> BeanUtils.isSimpleValueType(p.getParameterType()) || p.hasParameterAnnotation(WxApiBody.class))
                .findFirst().orElse(null);
        if (methodParameter == null) {
            throw new WxAppException("没有可处理的参数");
        }
        // 不是简单类型
        if (!BeanUtils.isSimpleValueType(methodParameter.getParameterType())) {
            // 暂时只支持json
            return args[methodParameter.getParameterIndex()];
        }
        if (args[methodParameter.getParameterIndex()] != null) {
            return args[methodParameter.getParameterIndex()].toString();
        } else {
            return "";
        }
    }

    private String getStringBody(WxApiMethodInfo wxApiMethodInfo, Object[] args) {
        MethodParameter methodParameter = wxApiMethodInfo.getMethodParameters().stream()
                .filter(p -> BeanUtils.isSimpleValueType(p.getParameterType()) || p.hasParameterAnnotation(WxApiBody.class))
                .findFirst().orElse(null);
        if (methodParameter == null) {
            throw new WxAppException("没有可处理的参数");
        }
        // 不是简单类型
        if (!BeanUtils.isSimpleValueType(methodParameter.getParameterType())) {
            try {
                // 暂时只支持json
                return jsonConverter.writeValueAsString(args[methodParameter.getParameterIndex()]);
            } catch (JsonProcessingException e) {
                logger.error(e.getMessage(), e);
            }
        }
        if (args[methodParameter.getParameterIndex()] != null) {
            return args[methodParameter.getParameterIndex()].toString();
        } else {
            return "";
        }
    }

    /**
     * 要发送文件，使用这种方式，请查看源码：FormHttpMessageConverter
     * @param wxApiMethodInfo
     * @param args
     * @return
     */
    private Object getFormBody(WxApiMethodInfo wxApiMethodInfo, Object[] args) {
        MultiValueMap<String, Object> params = new LinkedMultiValueMap<>();
        wxApiMethodInfo.getMethodParameters().stream()
                .filter(p -> !BeanUtils.isSimpleValueType(p.getParameterType()) || p.hasParameterAnnotation(WxApiForm.class) || p.hasParameterAnnotation(WxApiBody.class))
                .forEach(p -> {
                    WxApiForm wxApiForm = p.getParameterAnnotation(WxApiForm.class);
                    String paramName;
                    Object param;
                    if (wxApiForm == null || ValueConstants.DEFAULT_NONE.equals(wxApiForm.value())) {
                        paramName = p.getParameterName();
                    } else {
                        paramName = wxApiForm.value();
                    }
                    // 加入Assert
                    WxAppAssert.notNull(paramName, "请添加编译器的-parameter或者为参数添加注解名称");
                    if (WxRequestUtils.isMutlipart(p.getParameterType())) {
                        param = getFormResource(args[p.getParameterIndex()]);
                    } else {
                        param = args[p.getParameterIndex()];
                    }
                    params.add(paramName, param);
                });
        return params;
    }

    private Resource getFormResource(Object arg) {
        if (Resource.class.isAssignableFrom(arg.getClass())) {
            return (Resource) arg;
        } else if (InputStreamSource.class.isAssignableFrom(arg.getClass())) {
            try {
                return new InputStreamResource(((InputStreamResource) arg).getInputStream());
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                throw new WxAppException("处理IO转换异常", e);
            }
        } else if (File.class.isAssignableFrom(arg.getClass())) {
            return new FileSystemResource((File) arg);
        } else if (InputStream.class.isAssignableFrom(arg.getClass())) {
            return new InputStreamResource((InputStream) arg);
        } else {
            Reader reader = (Reader) arg;
            ReaderInputStream readerInputStream = new ReaderInputStream(reader, Charset.forName("UTF-8"));
            return new InputStreamResource(readerInputStream);
        }
    }

    /**
     * 获取一个application/json头
     *
     * @return
     */
    private HttpHeaders buildJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * text/xml头
     *
     * @return
     */
    private HttpHeaders buildXmlHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        return headers;
    }

}
