package withoutjjin.withoutjjin.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import withoutjjin.withoutjjin.domain.document.controller.DocumentUploadCompletionController;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UploadCompletionAsyncProperties.class)
public class UploadCompletionAsyncConfig implements WebMvcConfigurer {

    private final UploadCompletionAsyncProperties properties;

    public UploadCompletionAsyncConfig(UploadCompletionAsyncProperties properties) {
        this.properties = properties;
    }

    // 명시적으로 지정한 업로드 완료 호출만 사용하며 공용 실행기를 대체하지 않는다.
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor uploadCompletionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("upload-completion-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.registerDeferredResultInterceptors(new DeferredResultProcessingInterceptor() {
            @Override
            public <T> void beforeConcurrentHandling(NativeWebRequest request, DeferredResult<T> result) {
                if (isUploadCompletion(request)) {
                    AsyncWebRequest async = WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest();
                    if (async != null) {
                        // Future의 응답 제한이 먼저 처리되도록 이 API의 Servlet 제한에만 5초 여유를 둔다.
                        async.setTimeout(properties.responseTimeout().toMillis() + 5000L);
                    }
                }
            }

            @Override
            public <T> boolean handleTimeout(NativeWebRequest request, DeferredResult<T> result) {
                if (!isUploadCompletion(request)) return true;
                result.setErrorResult(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "업로드 완료 응답 대기시간을 초과했습니다. 업로드 상태를 확인한 뒤 다시 시도해 주세요."));
                return false;
            }
        });
    }

    private static boolean isUploadCompletion(NativeWebRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return handler instanceof HandlerMethod method
                && DocumentUploadCompletionController.class.isAssignableFrom(method.getBeanType());
    }
}
