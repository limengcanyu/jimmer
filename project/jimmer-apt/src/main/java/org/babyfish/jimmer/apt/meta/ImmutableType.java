package org.babyfish.jimmer.apt.meta;

import com.squareup.javapoet.ClassName;
import org.babyfish.jimmer.Formula;
import org.babyfish.jimmer.apt.Context;
import org.babyfish.jimmer.sql.*;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ImmutableType {

    public static final String PROP_EXPRESSION_SUFFIX = "PropExpression";

    private static final String FORMULA_CLASS_NAME = Formula.class.getName();

    private final TypeElement typeElement;

    private final boolean isEntity;

    private final boolean isMappedSuperClass;

    private final boolean isEmbeddable;

    private final String packageName;

    private final String name;

    private final String qualifiedName;

    private final Set<Modifier> modifiers;

    private final ImmutableType superType;

    private final Map<String, ImmutableProp> declaredProps;

    private Map<String, ImmutableProp> props;

    private List<ImmutableProp> propsOrderById;

    private ImmutableProp idProp;

    private ImmutableProp versionProp;

    private ImmutableProp logicalDeletedProp;

    private final ClassName className;

    private final ClassName draftClassName;

    private final ClassName producerClassName;

    private final ClassName implementorClassName;

    private final ClassName implClassName;

    private final ClassName draftImplClassName;

    private final ClassName mapStructClassName;

    private final ClassName tableClassName;

    private final ClassName tableExClassName;

    private final ClassName remoteTableClassName;

    private final ClassName fetcherClassName;

    private final ClassName propsClassName;

    private final ClassName propExpressionClassName;

    private final Map<ClassName, String> validationMessageMap;

    private final boolean acrossMicroServices;

    private final String microServiceName;

    public ImmutableType(
            Context context,
            TypeElement typeElement
    ) {
        this.typeElement = typeElement;
        Class<?> annotationType = context.getImmutableAnnotationType(typeElement);
        isEntity = annotationType == Entity.class;
        acrossMicroServices = annotationType == MappedSuperclass.class &&
                typeElement.getAnnotation(MappedSuperclass.class).acrossMicroServices();
        microServiceName = isEntity ?
                typeElement.getAnnotation(Entity.class).microServiceName() :
                annotationType == MappedSuperclass.class ?
                    typeElement.getAnnotation(MappedSuperclass.class).microServiceName() :
                    "";
        if (acrossMicroServices && !microServiceName.isEmpty()) {
            throw new MetaException(
                    typeElement,
                    "the `acrossMicroServices` of its annotation \"@" +
                            MappedSuperclass.class.getName() +
                            "\" is true so that `microServiceName` cannot be specified"
            );
        }
        isMappedSuperClass = annotationType == MappedSuperclass.class;
        isEmbeddable = annotationType == Embeddable.class;

        packageName = ((PackageElement)typeElement.getEnclosingElement()).getQualifiedName().toString();
        name = typeElement.getSimpleName().toString();
        qualifiedName = typeElement.getQualifiedName().toString();
        modifiers = typeElement.getModifiers();

        TypeMirror superTypeMirror = null;
        for (TypeMirror itf : typeElement.getInterfaces()) {
            if (context.isImmutable(itf)) {
                if (superTypeMirror != null) {
                    throw new MetaException(
                            typeElement,
                            "it inherits multiple Immutable interfaces"
                    );
                }
                superTypeMirror = itf;
            }
        }

        if (superTypeMirror != null) {
            superType = context.getImmutableType(superTypeMirror);
        } else {
            superType = null;
        }

        if (superType != null) {
            if (this.isEntity || this.isMappedSuperClass) {
                if (superType.isEntity()) {
                    throw new MetaException(
                            typeElement,
                            "it super type \"" +
                                    superType.qualifiedName +
                                    "\" is entity. " +
                                    "Super entity is not supported temporarily, " +
                                    "please use an interface decorated by @MappedSuperClass to be the super type"
                    );
                }
                if (!superType.isMappedSuperClass) {
                    throw new MetaException(
                            typeElement,
                            "it super type \"" +
                                    superType.qualifiedName +
                                    "\" is entity is not decorated by @MappedSuperClass"
                    );
                }
            } else if (superType.isEntity || superType.isMappedSuperClass) {
                throw new MetaException(
                        typeElement,
                        "it super type \"" +
                                superType.qualifiedName +
                                "\" cannot be decorated by @Entity or @MappedSuperClass"
                );
            }
            if (!superType.isAcrossMicroServices() && !superType.microServiceName.equals(microServiceName)) {
                throw new MetaException(
                        typeElement,
                        "its micro service name is \"" +
                                microServiceName +
                                "\" but the micro service name of its super type \"" +
                                superType.getQualifiedName() +
                                "\" is \"" +
                                superType.microServiceName +
                                "\""
                );
            }
        }

        int propIdSequence = superType != null ? superType.getProps().size() : 0;
        Map<String, ImmutableProp> map = new LinkedHashMap<>();
        List<ExecutableElement> executableElements = ElementFilter.methodsIn(typeElement.getEnclosedElements());
        for (ExecutableElement executableElement : executableElements) {
            if (executableElement.isDefault()) {
                for (AnnotationMirror am : executableElement.getAnnotationMirrors()) {
                    String qualifiedName = ((TypeElement)am.getAnnotationType().asElement()).getQualifiedName().toString();
                    if (qualifiedName.startsWith("org.babyfish.jimmer.") && !qualifiedName.equals(FORMULA_CLASS_NAME)) {
                        throw new MetaException(
                                executableElement,
                                "it " +
                                        "is default method so that it cannot be decorated by " +
                                        "any jimmer annotations except @" +
                                        FORMULA_CLASS_NAME
                        );
                    }
                }
            }
        }
        for (ExecutableElement executableElement : executableElements) {
            if (!executableElement.isDefault() && executableElement.getAnnotation(Id.class) != null) {
                ImmutableProp prop = new ImmutableProp(context, this, executableElement, ++propIdSequence);
                map.put(prop.getName(), prop);
            }
        }
        for (ExecutableElement executableElement : executableElements) {
            if (executableElement.isDefault()) {
                Formula formula = executableElement.getAnnotation(Formula.class);
                if (formula != null) {
                    if (!formula.sql().isEmpty()) {
                        throw new MetaException(
                                executableElement,
                                "it is non-abstract and decorated by @" +
                                        Formula.class.getName() +
                                        ", non-abstract modifier means simple calculation property based on " +
                                        "java expression so that the `sql` of that annotation cannot be specified"
                        );
                    }
                    if (formula.dependencies().length == 0) {
                        throw new MetaException(
                                executableElement,
                                "it is non-abstract and decorated by @" +
                                        Formula.class.getName() +
                                        ", non-abstract modifier means simple calculation property based on " +
                                        "java expression so that the `dependencies` of that annotation must be specified"
                        );
                    }
                    ImmutableProp prop = new ImmutableProp(context, this, executableElement, ++propIdSequence);
                    map.put(prop.getName(), prop);
                }
            } else if (executableElement.getAnnotation(Id.class) == null) {
                Formula formula = executableElement.getAnnotation(Formula.class);
                if (formula != null) {
                    if (formula.sql().isEmpty()) {
                        throw new MetaException(
                                executableElement,
                                "it is abstract and decorated by @" +
                                        Formula.class.getName() +
                                        ", abstract modifier means simple calculation property based on " +
                                        "SQL expression so that the `sql` of that annotation must be specified"
                        );
                    }
                    if (formula.dependencies().length != 0) {
                        throw new MetaException(
                                executableElement,
                                "it is abstract and decorated by @" +
                                        Formula.class.getName() +
                                        ", abstract modifier means simple calculation property based on " +
                                        "SQL expression so that the `dependencies` of that annotation cannot be specified"
                        );
                    }
                }
                ImmutableProp prop = new ImmutableProp(context, this, executableElement, ++propIdSequence);
                map.put(prop.getName(), prop);
            }
        }
        if (superType != null) {
            for (Map.Entry<String, ImmutableProp> e : map.entrySet()) {
                if (superType.getProps().containsKey(e.getKey())) {
                    throw new MetaException(
                            e.getValue().toElement(),
                            "it overrides property of super type, this is not allowed"
                    );
                }
            }
        }
        declaredProps = Collections.unmodifiableMap(map);
        List<ImmutableProp> idProps = declaredProps
                .values()
                .stream()
                .filter(it -> it.getAnnotation(Id.class) != null)
                .collect(Collectors.toList());
        List<ImmutableProp> versionProps = declaredProps
                .values()
                .stream()
                .filter(it -> it.getAnnotation(Version.class) != null)
                .collect(Collectors.toList());
        List<ImmutableProp> logicalDeletedProps = declaredProps
                .values()
                .stream()
                .filter(it -> it.getAnnotation(LogicalDeleted.class) != null)
                .collect(Collectors.toList());
        if (superType != null) {
            if (superType.getIdProp() != null && !idProps.isEmpty()) {
                throw new MetaException(
                        typeElement,
                        idProps.get(0) +
                                "\" cannot be decorated by `@" +
                                Id.class.getName() +
                                "` because id has been declared in super type"
                );
            }
            if (superType.getVersionProp() != null && !versionProps.isEmpty()) {
                throw new MetaException(
                        typeElement,
                        versionProps.get(0) +
                                "\" cannot be decorated by `@" +
                                Version.class.getName() +
                                "` because version has been declared in super type"
                );
            }
            if (superType.getLogicalDeletedProp() != null && !logicalDeletedProps.isEmpty()) {
                throw new MetaException(
                        typeElement,
                        logicalDeletedProps.get(0) +
                                "\" cannot be decorated by `@" +
                                LogicalDeleted.class.getName() +
                                "` because version has been declared in super type"
                );
            }
            idProp = superType.idProp;
            versionProp = superType.versionProp;
            logicalDeletedProp = superType.logicalDeletedProp;
        }
        if (!isEntity && !isMappedSuperClass) {
            if (!idProps.isEmpty()) {
                throw new MetaException(
                        typeElement,
                        idProps.get(0) +
                                "\" cannot be decorated by `@" +
                                Id.class.getName() +
                                "` because current type is not entity"
                );
            }
            if (!versionProps.isEmpty()) {
                throw new MetaException(
                        typeElement,
                                versionProps.get(0) +
                                "\" cannot be decorated by `@" +
                                Version.class.getName() +
                                "` because current type is not entity"
                );
            }
            if (!logicalDeletedProps.isEmpty()) {
                throw new MetaException(
                        typeElement,
                                logicalDeletedProps.get(0) +
                                "\" cannot be decorated by `@" +
                                LogicalDeleted.class.getName() +
                                "` because current type is not entity"
                );
            }
        } else {
            if (idProps.size() > 1) {
                throw new MetaException(
                        typeElement,
                        "multiple id properties are not supported, " +
                                "but both \"" +
                                idProps.get(0) +
                                "\" and \"" +
                                idProps.get(1) +
                                "\" is decorated by `@" +
                                LogicalDeleted.class.getName() +
                                "`"
                );
            }
            if (versionProps.size() > 1) {
                throw new MetaException(
                        typeElement,
                        "multiple version properties are not supported, " +
                                "but both \"" +
                                versionProps.get(0) +
                                "\" and \"" +
                                versionProps.get(1) +
                                "\" is decorated by `@" +
                                Version.class.getName() +
                                "`"
                );
            }
            if (logicalDeletedProps.size() > 1) {
                throw new MetaException(
                        typeElement,
                        "multiple logical deleted properties are not supported, " +
                                "but both \"" +
                                logicalDeletedProps.get(0) +
                                "\" and \"" +
                                logicalDeletedProps.get(1) +
                                "\" is decorated by `@" +
                                LogicalDeleted.class.getName() +
                                "`"
                );
            }
            if (idProp == null) {
                if (isEntity && idProps.isEmpty()) {
                    throw new MetaException(
                            typeElement,
                            "entity type must have an id property"
                    );
                }
                if (!idProps.isEmpty()) {
                    idProp = idProps.get(0);
                }
            }
            if (idProp != null && idProp.isAssociation(true)) {
                throw new MetaException(
                        typeElement,
                        "association cannot be id property"
                );
            }
            if (versionProp == null && !versionProps.isEmpty()) {
                versionProp = versionProps.get(0);
                if (versionProp.isAssociation(false)) {
                    throw new MetaException(
                            typeElement,
                            "association cannot be version property"
                    );
                }
            }
            if (logicalDeletedProp == null && !logicalDeletedProps.isEmpty()) {
                logicalDeletedProp = logicalDeletedProps.get(0);
                if (logicalDeletedProp.isAssociation(false)) {
                    throw new MetaException(
                            typeElement,
                            "it contains illegal property \"" +
                                    logicalDeletedProps +
                                    "\", association cannot be logical deleted property"
                    );
                }
            }
        }

        className = toClassName(null);
        draftClassName = toClassName(name -> name + "Draft");
        producerClassName = toClassName(name -> name + "Draft", "Producer");
        implementorClassName = toClassName(name -> name + "Draft", "Producer", "Implementor");
        implClassName = toClassName(name -> name + "Draft", "Producer", "Impl");
        draftImplClassName = toClassName(name -> name + "Draft", "Producer", "DraftImpl");
        mapStructClassName = toClassName(name -> name + "Draft", "MapStruct");
        tableClassName = toClassName(name -> name + "Table");
        tableExClassName = toClassName(name -> name + "TableEx");
        remoteTableClassName = toClassName(name -> name + "Table", "Remote");
        fetcherClassName = toClassName(name -> name + "Fetcher");
        propsClassName = toClassName(name -> name + "Props");
        propExpressionClassName = toClassName(name -> name + PROP_EXPRESSION_SUFFIX);

        validationMessageMap = ValidationMessages.parseMessageMap(typeElement);
    }

    public TypeElement getTypeElement() {
        return typeElement;
    }

    public boolean isEntity() {
        return isEntity;
    }

    public boolean isMappedSuperClass() {
        return isMappedSuperClass;
    }

    public boolean isEmbeddable() {
        return isEmbeddable;
    }

    public boolean isAcrossMicroServices() {
        return acrossMicroServices;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getName() {
        return name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public Set<Modifier> getModifiers() {
        return modifiers;
    }

    public ImmutableType getSuperType() {
        return superType;
    }

    public Map<String, ImmutableProp> getDeclaredProps() {
        return declaredProps;
    }

    public Map<String, ImmutableProp> getProps() {
        Map<String, ImmutableProp> props = this.props;
        if (props == null) {
            if (superType == null) {
                props = declaredProps;
            } else {
                props = new LinkedHashMap<>();
                for (ImmutableProp prop : superType.getProps().values()) {
                    if (prop.getAnnotation(Id.class) != null) {
                        props.put(prop.getName(), prop);
                    }
                }
                for (ImmutableProp prop : declaredProps.values()) {
                    if (prop.getAnnotation(Id.class) != null) {
                        props.put(prop.getName(), prop);
                    }
                }
                for (ImmutableProp prop : superType.getProps().values()) {
                    if (prop.getAnnotation(Id.class) == null) {
                        props.put(prop.getName(), prop);
                    }
                }
                for (ImmutableProp prop : declaredProps.values()) {
                    if (prop.getAnnotation(Id.class) == null) {
                        props.put(prop.getName(), prop);
                    }
                }
            }
            this.props = props;
        }
        return props;
    }

    public List<ImmutableProp> getPropsOrderById() {
        List<ImmutableProp> list = propsOrderById;
        if (list == null) {
            this.propsOrderById = list = getProps()
                    .values()
                    .stream()
                    .sorted(Comparator.comparing(ImmutableProp::getId))
                    .collect(Collectors.toList());
        }
        return list;
    }

    public ImmutableProp getIdProp() {
        return idProp;
    }

    public ImmutableProp getVersionProp() {
        return versionProp;
    }

    public ImmutableProp getLogicalDeletedProp() {
        return logicalDeletedProp;
    }

    public ClassName getClassName() {
        return className;
    }

    public ClassName getDraftClassName() {
        return draftClassName;
    }

    public ClassName getProducerClassName() {
        return producerClassName;
    }

    public ClassName getImplementorClassName() {
        return implementorClassName;
    }

    public ClassName getImplClassName() {
        return implClassName;
    }

    public ClassName getDraftImplClassName() {
        return draftImplClassName;
    }

    public ClassName getMapStructClassName() {
        return mapStructClassName;
    }

    public ClassName getTableClassName() {
        return tableClassName;
    }

    public ClassName getTableExClassName() {
        return tableExClassName;
    }

    public ClassName getRemoteTableClassName() {
        return remoteTableClassName;
    }

    public ClassName getFetcherClassName() {
        return fetcherClassName;
    }

    public ClassName getPropsClassName() {
        return propsClassName;
    }

    public ClassName getPropExpressionClassName() {
        return propExpressionClassName;
    }

    private ClassName toClassName(
            Function<String, String> transform,
            String ... moreSimpleNames
    ) {
        return ClassName.get(
                packageName,
                transform != null ? transform.apply(name) : name,
                moreSimpleNames
        );
    }

    public Map<ClassName, String> getValidationMessageMap() {
        return validationMessageMap;
    }

    public String getMicroServiceName() {
        return microServiceName;
    }

    public boolean resolve(Context context, int step) {
        boolean hasNext = false;
        for (ImmutableProp prop : declaredProps.values()) {
            hasNext |= prop.resolve(context, step);
        }
        return hasNext;
    }

    @Override
    public String toString() {
        return typeElement.getQualifiedName().toString();
    }
}
