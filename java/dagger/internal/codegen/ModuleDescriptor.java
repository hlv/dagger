/*
 * Copyright (C) 2015 The Dagger Authors.
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

package dagger.internal.codegen;

import static com.google.auto.common.MoreElements.getPackage;
import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static dagger.internal.codegen.ConfigurationAnnotations.getModuleAnnotation;
import static dagger.internal.codegen.ConfigurationAnnotations.getModuleIncludes;
import static dagger.internal.codegen.DaggerElements.getAnnotationMirror;
import static dagger.internal.codegen.DaggerElements.isAnnotationPresent;
import static dagger.internal.codegen.SourceFiles.classFileName;
import static javax.lang.model.type.TypeKind.DECLARED;
import static javax.lang.model.type.TypeKind.NONE;
import static javax.lang.model.util.ElementFilter.methodsIn;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.squareup.javapoet.ClassName;
import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dagger.producers.ProducerModule;
import dagger.producers.Produces;
import java.lang.annotation.Annotation;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

@AutoValue
abstract class ModuleDescriptor {

  abstract TypeElement moduleElement();

  abstract ImmutableSet<ModuleDescriptor> includedModules();

  abstract ImmutableSet<ContributionBinding> bindings();

  /** The multibinding declarations contained in this module. */
  abstract ImmutableSet<MultibindingDeclaration> multibindingDeclarations();

  /** The {@link Module#subcomponents() subcomponent declarations} contained in this module. */
  abstract ImmutableSet<SubcomponentDeclaration> subcomponentDeclarations();

  /** The {@link Binds} method declarations that define delegate bindings. */
  abstract ImmutableSet<DelegateDeclaration> delegateDeclarations();

  /** The {@link BindsOptionalOf} method declarations that define optional bindings. */
  abstract ImmutableSet<OptionalBindingDeclaration> optionalDeclarations();

  abstract Kind kind();

  enum Kind {
    MODULE(Module.class, Provides.class),
    PRODUCER_MODULE(ProducerModule.class, Produces.class);

    private final Class<? extends Annotation> moduleAnnotation;
    private final Class<? extends Annotation> methodAnnotation;

    /**
     * Returns the kind of an annotated element if it is annotated with one of the {@linkplain
     * #moduleAnnotation() annotation types}.
     *
     * @throws IllegalArgumentException if the element is annotated with more than one of the
     *     annotation types
     */
    static Optional<Kind> forAnnotatedElement(TypeElement element) {
      Set<Kind> kinds = EnumSet.noneOf(Kind.class);
      for (Kind kind : values()) {
        if (MoreElements.isAnnotationPresent(element, kind.moduleAnnotation())) {
          kinds.add(kind);
        }
      }
      checkArgument(
          kinds.size() <= 1, "%s cannot be annotated with more than one of %s", element, kinds);
      return kinds.stream().findFirst();
    }

    Kind(
        Class<? extends Annotation> moduleAnnotation,
        Class<? extends Annotation> methodAnnotation) {
      this.moduleAnnotation = moduleAnnotation;
      this.methodAnnotation = methodAnnotation;
    }

    Optional<AnnotationMirror> getModuleAnnotationMirror(TypeElement element) {
      return getAnnotationMirror(element, moduleAnnotation);
    }

    Class<? extends Annotation> moduleAnnotation() {
      return moduleAnnotation;
    }

    Class<? extends Annotation> methodAnnotation() {
      return methodAnnotation;
    }

    ImmutableSet<Kind> includesKinds() {
      switch (this) {
        case MODULE:
          return Sets.immutableEnumSet(MODULE);
        case PRODUCER_MODULE:
          return Sets.immutableEnumSet(MODULE, PRODUCER_MODULE);
        default:
          throw new AssertionError(this);
      }
    }
  }

  static final class Factory {
    private final DaggerElements elements;
    private final ProvisionBinding.Factory provisionBindingFactory;
    private final ProductionBinding.Factory productionBindingFactory;
    private final MultibindingDeclaration.Factory multibindingDeclarationFactory;
    private final DelegateDeclaration.Factory bindingDelegateDeclarationFactory;
    private final SubcomponentDeclaration.Factory subcomponentDeclarationFactory;
    private final OptionalBindingDeclaration.Factory optionalBindingDeclarationFactory;

    Factory(
        DaggerElements elements,
        ProvisionBinding.Factory provisionBindingFactory,
        ProductionBinding.Factory productionBindingFactory,
        MultibindingDeclaration.Factory multibindingDeclarationFactory,
        DelegateDeclaration.Factory bindingDelegateDeclarationFactory,
        SubcomponentDeclaration.Factory subcomponentDeclarationFactory,
        OptionalBindingDeclaration.Factory optionalBindingDeclarationFactory) {
      this.elements = elements;
      this.provisionBindingFactory = provisionBindingFactory;
      this.productionBindingFactory = productionBindingFactory;
      this.multibindingDeclarationFactory = multibindingDeclarationFactory;
      this.bindingDelegateDeclarationFactory = bindingDelegateDeclarationFactory;
      this.subcomponentDeclarationFactory = subcomponentDeclarationFactory;
      this.optionalBindingDeclarationFactory = optionalBindingDeclarationFactory;
    }

    ModuleDescriptor create(TypeElement moduleElement) {
      ImmutableSet.Builder<ContributionBinding> bindings = ImmutableSet.builder();
      ImmutableSet.Builder<DelegateDeclaration> delegates = ImmutableSet.builder();
      ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarations =
          ImmutableSet.builder();
      ImmutableSet.Builder<OptionalBindingDeclaration> optionalDeclarations =
          ImmutableSet.builder();

      for (ExecutableElement moduleMethod : methodsIn(elements.getAllMembers(moduleElement))) {
        if (isAnnotationPresent(moduleMethod, Provides.class)) {
          bindings.add(provisionBindingFactory.forProvidesMethod(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, Produces.class)) {
          bindings.add(productionBindingFactory.forProducesMethod(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, Binds.class)) {
          delegates.add(bindingDelegateDeclarationFactory.create(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, Multibinds.class)) {
          multibindingDeclarations.add(
              multibindingDeclarationFactory.forMultibindsMethod(moduleMethod, moduleElement));
        }
        if (isAnnotationPresent(moduleMethod, BindsOptionalOf.class)) {
          optionalDeclarations.add(
              optionalBindingDeclarationFactory.forMethod(moduleMethod, moduleElement));
        }
      }

      return new AutoValue_ModuleDescriptor(
          moduleElement,
          ImmutableSet.copyOf(collectIncludedModules(new LinkedHashSet<>(), moduleElement)),
          bindings.build(),
          multibindingDeclarations.build(),
          subcomponentDeclarationFactory.forModule(moduleElement),
          delegates.build(),
          optionalDeclarations.build(),
          Kind.forAnnotatedElement(moduleElement).get());
    }

    @CanIgnoreReturnValue
    private Set<ModuleDescriptor> collectIncludedModules(
        Set<ModuleDescriptor> includedModules, TypeElement moduleElement) {
      TypeMirror superclass = moduleElement.getSuperclass();
      if (!superclass.getKind().equals(NONE)) {
        verify(superclass.getKind().equals(DECLARED));
        TypeElement superclassElement = MoreTypes.asTypeElement(superclass);
        if (!superclassElement.getQualifiedName().contentEquals(Object.class.getCanonicalName())) {
          collectIncludedModules(includedModules, superclassElement);
        }
      }
      Optional<AnnotationMirror> moduleAnnotation = getModuleAnnotation(moduleElement);
      if (moduleAnnotation.isPresent()) {
        getModuleIncludes(moduleAnnotation.get())
            .stream()
            .map(MoreTypes::asTypeElement)
            .map(this::create)
            .forEach(includedModules::add);

        collectImplicitlyIncludedModules(includedModules, moduleElement);
      }
      return includedModules;
    }

    // @ContributesAndroidInjector generates a module that is implicitly included in the enclosing
    // module
    private void collectImplicitlyIncludedModules(
        Set<ModuleDescriptor> includedModules, TypeElement moduleElement) {
      TypeElement contributesAndroidInjector =
          elements.getTypeElement("dagger.android.ContributesAndroidInjector");
      if (contributesAndroidInjector == null) {
        return;
      }
      for (ExecutableElement method : methodsIn(moduleElement.getEnclosedElements())) {
        if (isAnnotationPresent(method, contributesAndroidInjector.asType())) {
          includedModules.add(
              create(elements.checkTypePresent(implicitlyIncludedModuleName(method))));
        }
      }
    }

    private String implicitlyIncludedModuleName(ExecutableElement method) {
      return getPackage(method).getQualifiedName()
          + "."
          + classFileName(ClassName.get(MoreElements.asType(method.getEnclosingElement())))
          + "_"
          + LOWER_CAMEL.to(UPPER_CAMEL, method.getSimpleName().toString());
    }
  }
}
