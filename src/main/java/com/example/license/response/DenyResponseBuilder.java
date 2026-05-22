package com.example.license.response;

import com.example.license.config.DenyConfig;
import com.example.license.validator.ValidationResult;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.envoyproxy.envoy.type.v3.StatusCode;
import com.google.rpc.Code;
import com.google.rpc.Status;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Builds Envoy {@link CheckResponse} protos for allow and deny outcomes.
 */
@Component
public class DenyResponseBuilder {

    private static final CheckResponse ALLOW_RESPONSE = CheckResponse.newBuilder()
            .setStatus(Status.newBuilder().setCode(Code.OK_VALUE).build())
            .setOkResponse(OkHttpResponse.newBuilder().build())
            .build();

    public CheckResponse allow() {
        return ALLOW_RESPONSE;
    }

    /**
     * Builds a deny response using the validator result, optionally overriding headers/body
     * from the rule's {@link DenyConfig}.
     */
    public CheckResponse deny(ValidationResult result, DenyConfig ruleConfig) {
        int status = result.denyStatus() > 0 ? result.denyStatus() : ruleConfig.status();
        String body = result.denyBody() != null ? result.denyBody() : ruleConfig.body();
        Map<String, String> headers = (result.denyHeaders() != null && !result.denyHeaders().isEmpty())
                ? result.denyHeaders()
                : ruleConfig.headers();

        StatusCode code = StatusCode.forNumber(status);
        if (code == null || code == StatusCode.Empty) {
            code = StatusCode.Forbidden;
        }

        DeniedHttpResponse.Builder deniedBuilder = DeniedHttpResponse.newBuilder()
                .setStatus(HttpStatus.newBuilder().setCode(code).build())
                .setBody(body != null ? body : "");

        if (headers != null) {
            headers.forEach((name, value) -> deniedBuilder.addHeaders(
                    HeaderValueOption.newBuilder()
                            .setHeader(HeaderValue.newBuilder().setKey(name).setValue(value).build())
                            .build()));
        }

        return CheckResponse.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.PERMISSION_DENIED_VALUE).build())
                .setDeniedResponse(deniedBuilder.build())
                .build();
    }
}
