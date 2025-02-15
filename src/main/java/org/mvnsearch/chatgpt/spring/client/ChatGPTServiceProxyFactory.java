package org.mvnsearch.chatgpt.spring.client;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.mvnsearch.chatgpt.model.*;
import org.mvnsearch.chatgpt.model.function.GPTFunctionUtils;
import org.mvnsearch.chatgpt.spring.service.ChatGPTService;
import org.mvnsearch.chatgpt.spring.service.PromptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ProxyHints;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class ChatGPTServiceProxyFactory {

	private final ChatGPTService chatGPTService;

	private final PromptManager promptManager;

	public ChatGPTServiceProxyFactory(ChatGPTService chatGPTService, PromptManager promptManager) {
		this.chatGPTService = chatGPTService;
		this.promptManager = promptManager;
	}

	@SuppressWarnings("unchecked")
	public <T> T createClient(Class<T> clazz) {
		return ProxyFactory.getProxy(clazz,
				new GPTExchangeMethodInterceptor(this.chatGPTService, this.promptManager, clazz));
	}

}

class GPTExchangeMethodInterceptor implements MethodInterceptor {

	private final ChatGPTService chatGPTService;

	private final PromptManager promptManager;

	private final GPTExchange gptExchangeAnnotation;

	GPTExchangeMethodInterceptor(ChatGPTService chatGPTService, PromptManager promptManager, Class<?> interfaceClass) {
		this.chatGPTService = chatGPTService;
		this.promptManager = promptManager;
		this.gptExchangeAnnotation = interfaceClass.getAnnotation(GPTExchange.class);
	}

	public String formatChatMessage(String role, String content, Object[] args) {
		if (args != null && args.length > 0) {
			if (content.contains("{") && content.contains("}")) {
				if (args.length == 1 && args[0].getClass().isRecord()) {
					content = MessageFormat.format(content, GPTFunctionUtils.convertRecordToArray(args[0]));
				}
				else {
					content = MessageFormat.format(content, args);
				}
			}
			else if (Objects.equals(role, "user")) {
				StringBuilder sb = new StringBuilder(content);
				for (Object arg : args) {
					if (arg != null) {
						sb.append(" ").append(arg);
					}
				}
				content = sb.toString();
			}
		}
		return content;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Method method = invocation.getMethod();
		Object[] args = invocation.getArguments();
		List<ChatMessage> messages = new ArrayList<>();
		String[] functions = null;
		final ChatCompletion chatCompletionAnnotation = method.getAnnotation(ChatCompletion.class);
		if (chatCompletionAnnotation != null) {
			functions = chatCompletionAnnotation.functions();
			// user message
			String userMessage = chatCompletionAnnotation.value();
			if (userMessage.isEmpty() && !chatCompletionAnnotation.userTemplate().isEmpty()) {
				userMessage = promptManager.prompt(chatCompletionAnnotation.userTemplate(), args);
			}
			else {
				userMessage = formatChatMessage("user", userMessage, args);
			}
			messages.add(ChatMessage.userMessage(userMessage));
			// system message
			String systemMessage = chatCompletionAnnotation.system();
			if (systemMessage.isEmpty() && !chatCompletionAnnotation.systemTemplate().isEmpty()) {
				systemMessage = promptManager.prompt(chatCompletionAnnotation.systemTemplate(), args);
			}
			else if (!systemMessage.isEmpty()) {
				systemMessage = formatChatMessage("system", systemMessage, args);
			}
			if (!systemMessage.isEmpty()) {
				messages.add(ChatMessage.systemMessage(systemMessage));
			}
			// assistant message
			String assistantMessage = chatCompletionAnnotation.assistant();
			if (assistantMessage.isEmpty() && !chatCompletionAnnotation.assistantTemplate().isEmpty()) {
				assistantMessage = promptManager.prompt(chatCompletionAnnotation.assistantTemplate(), args);
			}
			else if (!assistantMessage.isEmpty()) {
				assistantMessage = formatChatMessage("assistant", assistantMessage, args);
			}
			if (!assistantMessage.isEmpty()) {
				messages.add(ChatMessage.assistantMessage(assistantMessage));
			}
		}
		else {
			String userMessage = method.getName();
			userMessage = userMessage.replaceAll("([A-Z])", " $1").trim();
			messages.add(ChatMessage.userMessage(userMessage));
		}
		ChatCompletionRequest request = new ChatCompletionRequest();
		request.setMessages(messages);
		// inject global configuration
		if (gptExchangeAnnotation != null) {
			if ((functions == null || functions.length == 0)) {
				functions = gptExchangeAnnotation.functions();
			}
			if (!gptExchangeAnnotation.value().isEmpty()) {
				request.setModel(gptExchangeAnnotation.value());
			}
			if (gptExchangeAnnotation.temperature() >= 0) {
				request.setTemperature(gptExchangeAnnotation.temperature());
			}
			if (gptExchangeAnnotation.maxTokens() > 0) {
				request.setMaxTokens(gptExchangeAnnotation.maxTokens());
			}
			if (gptExchangeAnnotation.value() != null && !gptExchangeAnnotation.value().isEmpty()) {
				request.setModel(gptExchangeAnnotation.value());
			}
			if (functions == null || functions.length == 0) {
				functions = gptExchangeAnnotation.functions();
			}
		}
		if (functions != null && functions.length > 0) {
			request.setFunctionNames(Arrays.stream(functions).toList());
			return chatGPTService.chat(request).flatMap(ChatCompletionResponse::getReplyCombinedText);
		}
		else {
			return chatGPTService.chat(request).map(ChatCompletionResponse::getReplyText);
		}
	}

}

class GPTExchangeBeanRegistrationAotProcessor implements BeanRegistrationAotProcessor {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final static MergedAnnotations.Search SEARCH = MergedAnnotations
		.search(MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);

	@Nullable
	public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
		Class<?> beanClass = registeredBean.getBeanClass();
		List<Class<?>> exchangeInterfaces = new ArrayList<>();

		Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(beanClass);
		if (log.isDebugEnabled())
			log.debug("GPTExchange interfaces: {}", Arrays.toString(interfaces));
		for (Class<?> interfaceClass : interfaces) {
			ReflectionUtils.doWithMethods(interfaceClass, (method) -> {
				if (!exchangeInterfaces.contains(interfaceClass)
						&& SEARCH.from(method).isPresent(ChatCompletion.class)) {
					exchangeInterfaces.add(interfaceClass);
					if (log.isDebugEnabled()) {
						log.debug("adding {} to the collection of GPTExchange interfaces", interfaceClass.getName());
					}
				}
			});
		}

		return !exchangeInterfaces.isEmpty() ? new HttpExchangeBeanRegistrationAotContribution(exchangeInterfaces)
				: null;
	}

	private static class HttpExchangeBeanRegistrationAotContribution implements BeanRegistrationAotContribution {

		private final List<Class<?>> httpExchangeInterfaces;

		HttpExchangeBeanRegistrationAotContribution(List<Class<?>> httpExchangeInterfaces) {
			this.httpExchangeInterfaces = httpExchangeInterfaces;
		}

		public void applyTo(GenerationContext generationContext, BeanRegistrationCode beanRegistrationCode) {
			ProxyHints proxyHints = generationContext.getRuntimeHints().proxies();
			ReflectionHints reflectionHints = generationContext.getRuntimeHints().reflection();
			for (Class<?> exchangeInterface : this.httpExchangeInterfaces) {
				proxyHints.registerJdkProxy(AopProxyUtils.completeJdkProxyInterfaces(exchangeInterface));
				ReflectionUtils.doWithMethods(exchangeInterface, method -> {
					if (SEARCH.from(method).isPresent(ChatCompletion.class)) {
						Stream.of(method.getParameterTypes())
							.forEach(c -> reflectionHints.registerType(c, MemberCategory.values()));
					}
				});

			}

		}

	}

}
