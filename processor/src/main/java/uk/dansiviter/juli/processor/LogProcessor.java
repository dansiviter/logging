/*
 * Copyright 2021 Daniel Siviter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.dansiviter.juli.processor;

import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.tools.Diagnostic.Kind.NOTE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Generated;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.Feature.BeforeAnalysisAccess;

import uk.dansiviter.juli.BaseLog;
import uk.dansiviter.juli.LogProducer;
import uk.dansiviter.juli.annotations.Log;
import uk.dansiviter.juli.annotations.Message;
import uk.dansiviter.juli.annotations.Message.Level;

/**
 * Processes {@link Log} annotations.
 */
@SupportedAnnotationTypes("uk.dansiviter.juli.annotations.Log")
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class LogProcessor extends AbstractProcessor {

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		annotations.forEach(a -> roundEnv.getElementsAnnotatedWith(a).forEach(e -> process((TypeElement) e)));
		return true;
	}

	private void process(@Nonnull TypeElement element) {
		var pkg = this.processingEnv.getElementUtils().getPackageOf(element);
		var type = element.asType();
		var className = className(element);
		var concreteName = className.concat(LogProducer.SUFFIX);
		createConcrete(className, element, type, concreteName, pkg);
	}

	private void createConcrete(
		@Nonnull String className,
		@Nonnull TypeElement element,
		@Nonnull TypeMirror type,
		@Nonnull String concreteName,
		@Nonnull PackageElement pkg)
	{
		processingEnv.getMessager().printMessage(NOTE, "Generating class for: " + className, element);

		var constructor = MethodSpec.constructorBuilder()
				.addModifiers(Modifier.PUBLIC)
				.addParameter(String.class, "name")
				.addStatement("this.log = $T.class.getAnnotation($T.class)", type, Log.class)
				.addStatement("this.key = $T.key($T.class, name)", LogProducer.class, type)
				.addStatement("this.delegate = delegate(name)")
				.build();
		var delegateMethod = MethodSpec.methodBuilder("delegate")
				.addAnnotation(Override.class)
				.addModifiers(PUBLIC, FINAL)
				.returns(Logger.class)
				.addStatement("return this.delegate")
				.addJavadoc("@returns the delegate logger.")
				.build();
		var logMethod = MethodSpec.methodBuilder("log")
				.addAnnotation(Override.class)
				.addModifiers(PUBLIC, FINAL)
				.returns(Log.class)
				.addStatement("return this.log")
				.addJavadoc("@returns the annotation instance.")
				.build();

		var typeBuilder = TypeSpec.classBuilder(concreteName)
				.addModifiers(PUBLIC, FINAL)
				.addAnnotation(AnnotationSpec
					.builder(Generated.class)
					.addMember("value", "\"" + getClass().getName() + "\"")
					.addMember("comments", "\"https://juli.dansiviter.uk/\"")
					.build())
				.addSuperinterface(BaseLog.class)
				.addSuperinterface(type)
				.addMethod(constructor)
				.addField(Log.class, "log", PRIVATE, FINAL)
				.addMethod(logMethod)
				.addField(Logger.class, "delegate", PRIVATE, FINAL)
				.addMethod(delegateMethod)
				.addField(String.class, "key", PUBLIC, FINAL);  // purposefully public

		element.getEnclosedElements().stream()
			.filter(e -> e.getKind() == ElementKind.METHOD)
			.filter(e -> e.getAnnotation(Message.class) != null)
			.forEach(e -> processMethod(typeBuilder, (ExecutableElement) e));

		typeBuilder.addType(createGraalFeature(className, element, concreteName, pkg));

		var javaFile = JavaFile.builder(pkg.getQualifiedName().toString(), typeBuilder.build()).build();

		try {
			javaFile.writeTo(processingEnv.getFiler());
		} catch (IOException e) {
			processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), element);
		}
	}

	private void processMethod(@Nonnull TypeSpec.Builder builder, @Nonnull ExecutableElement e) {
		var message = e.getAnnotation(Message.class);

		if (message.value() == null || message.value().isEmpty()) {
			processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Message cannot be empty!", e);
		}

		var method = MethodSpec.methodBuilder(e.getSimpleName().toString())
				.addAnnotation(Override.class)
				.addModifiers(PUBLIC)
				.returns(TypeName.get(e.getReturnType()));

		e.getParameters().forEach(p -> method.addParameter(TypeName.get(p.asType()), p.getSimpleName().toString()));

		var returnThis = builder.superinterfaces.contains(TypeName.get(e.getReturnType()));

		method.beginControlFlow("if (!isLoggable($T.$N))", Level.class, message.level().name())
					.addStatement(returnThis ? "return this" : "return")
					.endControlFlow();

		if (message.once()) {
			var onceField = "ONCE__".concat(e.getSimpleName().toString());
			var onceSpec = FieldSpec.builder(AtomicBoolean.class, onceField, PRIVATE, STATIC, FINAL)
					.initializer("new $T()", AtomicBoolean.class)
				  .build();
			builder.addField(onceSpec);
			method.beginControlFlow("if ($N.getAndSet(true))", onceField)
					.addStatement(returnThis ? "return this" : "return")
					.endControlFlow();
		}

		var statement = new StringBuilder("logp($T.$N, \"$N\"");
		for (VariableElement ve : e.getParameters()) {
			statement.append(", ").append(ve.getSimpleName());
		}
		statement.append(')');

		method.addStatement(statement.toString(), Level.class, message.level().name(), message.value());

		if (returnThis) {
			method.addStatement("return this");
		}

		builder.addMethod(method.build());
	}

	private TypeSpec createGraalFeature(
		@Nonnull String className,
		@Nonnull TypeElement element,
		@Nonnull String concreteName,
		@Nonnull PackageElement pkg)
	{
		processingEnv.getMessager().printMessage(NOTE, "Generating class for: " + className, element);

		var beforeAnalysisMethod = MethodSpec.methodBuilder("beforeAnalysis")
			.addAnnotation(Override.class)
			.addModifiers(PUBLIC, FINAL)
			.addParameter(BeforeAnalysisAccess.class, "access")
			.addStatement("var clazz = access.findClassByName(\"$N.$N\")", pkg.getQualifiedName(), concreteName)
			.addStatement("$T.register(clazz)", RuntimeReflection.class)
			.addStatement("$T.register(clazz.getDeclaredConstructors())", RuntimeReflection.class)
			.addStatement("$T.register(clazz.getDeclaredFields())", RuntimeReflection.class)
			.addStatement("$T.register(clazz.getDeclaredMethods())", RuntimeReflection.class)
			.build();

		return TypeSpec.classBuilder("GraalFeature")
				.addModifiers(PUBLIC, STATIC, FINAL)
				.addSuperinterface(Feature.class)
				.addAnnotation(AutomaticFeature.class)
				.addMethod(beforeAnalysisMethod)
				.build();
	}

	private static String className(@Nonnull TypeElement typeElement) {
		var types = new ArrayList<CharSequence>();

		Element e = typeElement;
		while (e instanceof TypeElement) {
			types.add(0, ((TypeElement) e).getSimpleName());
			e = e.getEnclosingElement();
		}

		return String.join("$", types);
	}
}
