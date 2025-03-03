// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.transformers;

import static com.android.tools.r8.references.Reference.classFromTypeName;
import static com.android.tools.r8.transformers.ClassFileTransformer.InnerClassPredicate.always;
import static com.android.tools.r8.utils.DescriptorUtils.getBinaryNameFromDescriptor;
import static com.android.tools.r8.utils.StringUtils.replaceAll;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.ASM9;

import com.android.tools.r8.TestRuntime.CfVm;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.cf.CfVersion;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AccessFlags;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.FieldReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.transformers.MethodTransformer.MethodContext;
import com.android.tools.r8.utils.DescriptorUtils;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class ClassFileTransformer {

  /**
   * Basic algorithm for transforming the content of a class file.
   *
   * <p>The provided transformers are nested in the order given: the first in the list will receive
   * is call back first, if it forwards to 'super' then the seconds call back will be called, etc,
   * until finally the writer will be called. If the writer is not called the effect is as if the
   * callback was never called and its content will not be in the result.
   */
  public static byte[] transform(
      byte[] bytes,
      List<ClassTransformer> classTransformers,
      List<MethodTransformer> methodTransformers,
      int flags) {
    ClassReader reader = new ClassReader(bytes);
    ClassWriter writer = new ClassWriter(reader, flags);
    ClassVisitor subvisitor = new InnerMostClassTransformer(writer, methodTransformers);
    for (int i = classTransformers.size() - 1; i >= 0; i--) {
      classTransformers.get(i).setSubVisitor(subvisitor);
      subvisitor = classTransformers.get(i);
    }
    reader.accept(subvisitor, 0);
    return writer.toByteArray();
  }

  // Inner-most bride from the class transformation to the method transformers.
  private static class InnerMostClassTransformer extends ClassVisitor {
    ClassReference classReference;
    final List<MethodTransformer> methodTransformers;

    InnerMostClassTransformer(ClassWriter writer, List<MethodTransformer> methodTransformers) {
      super(ASM7, writer);
      this.methodTransformers = methodTransformers;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      super.visit(version, access, name, signature, superName, interfaces);
      classReference = Reference.classFromBinaryName(name);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodContext context = createMethodContext(access, name, descriptor);
      MethodVisitor subvisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
      for (int i = methodTransformers.size() - 1; i >= 0; i--) {
        MethodTransformer transformer = methodTransformers.get(i);
        transformer.setSubVisitor(subvisitor);
        transformer.setContext(context);
        subvisitor = transformer;
      }
      return subvisitor;
    }

    private MethodContext createMethodContext(int access, String name, String descriptor) {
      // Maybe clean up this parsing of info as it is not very nice.
      MethodSignature methodSignature = MethodSignature.fromSignature(name, descriptor);
      MethodReference methodReference =
          Reference.method(
              classReference,
              name,
              Arrays.stream(methodSignature.parameters)
                  .map(DescriptorUtils::javaTypeToDescriptor)
                  .map(Reference::typeFromDescriptor)
                  .collect(Collectors.toList()),
              methodSignature.type.equals("void")
                  ? null
                  : Reference.typeFromDescriptor(
                      DescriptorUtils.javaTypeToDescriptor(methodSignature.type)));
      return new MethodContext(methodReference, access);
    }
  }

  // Transformer utilities.

  private final byte[] bytes;
  private final ClassReference classReference;
  private final List<ClassTransformer> classTransformers = new ArrayList<>();
  private final List<MethodTransformer> methodTransformers = new ArrayList<>();

  private ClassFileTransformer(byte[] bytes, ClassReference classReference) {
    this.bytes = bytes;
    this.classReference = classReference;
  }

  public static ClassFileTransformer create(byte[] bytes, ClassReference classReference) {
    return new ClassFileTransformer(bytes, classReference);
  }

  public static ClassFileTransformer create(Class<?> clazz) throws IOException {
    return create(ToolHelper.getClassAsBytes(clazz), classFromTypeName(clazz.getTypeName()));
  }

  public byte[] transform() {
    return transform(0);
  }

  public byte[] transform(int flags) {
    return ClassFileTransformer.transform(bytes, classTransformers, methodTransformers, flags);
  }

  /** Base addition of a transformer on the class. */
  public ClassFileTransformer addClassTransformer(ClassTransformer transformer) {
    classTransformers.add(transformer);
    return this;
  }

  /** Base addition of a transformer on methods. */
  public ClassFileTransformer addMethodTransformer(MethodTransformer transformer) {
    methodTransformers.add(transformer);
    return this;
  }

  public ClassReference getClassReference() {
    return classReference;
  }

  /** Unconditionally replace the implements clause of a class. */
  public ClassFileTransformer setImplements(Class<?>... interfaces) {
    return setImplementsClassDescriptors(
        Arrays.stream(interfaces)
            .map(clazz -> DescriptorUtils.javaTypeToDescriptor(clazz.getTypeName()))
            .toArray(String[]::new));
  }

  /** Unconditionally replace the implements clause of a class. */
  public ClassFileTransformer setImplementsClassDescriptors(String... classDescriptors) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] ignoredInterfaces) {
            super.visit(
                version,
                access,
                name,
                signature,
                superName,
                Arrays.stream(classDescriptors)
                    .map(DescriptorUtils::getBinaryNameFromDescriptor)
                    .toArray(String[]::new));
          }
        });
  }

  /** Unconditionally replace the descriptor (ie, qualified name) of a class. */
  public ClassFileTransformer setClassDescriptor(String newDescriptor) {
    assert DescriptorUtils.isClassDescriptor(newDescriptor);
    String newBinaryName = getBinaryNameFromDescriptor(newDescriptor);
    return addClassTransformer(
            new ClassTransformer() {
              @Override
              public void visit(
                  int version,
                  int access,
                  String binaryName,
                  String signature,
                  String superName,
                  String[] interfaces) {
                super.visit(version, access, newBinaryName, signature, superName, interfaces);
              }

              @Override
              public FieldVisitor visitField(
                  int access, String name, String descriptor, String signature, Object object) {
                return super.visitField(
                    access,
                    name,
                    replaceAll(descriptor, getClassReference().getDescriptor(), newDescriptor),
                    signature,
                    object);
              }

              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String name,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                return super.visitMethod(
                    access,
                    name,
                    replaceAll(descriptor, getClassReference().getDescriptor(), newDescriptor),
                    signature,
                    exceptions);
              }
            })
        .replaceClassDescriptorInMethodInstructions(
            getClassReference().getDescriptor(), newDescriptor);
  }

  public ClassFileTransformer setVersion(int newVersion) {
    return setVersion(CfVersion.fromRaw(newVersion));
  }

  public ClassFileTransformer setVersion(CfVersion newVersion) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            super.visit(newVersion.raw(), access, name, signature, superName, interfaces);
          }
        });
  }

  public ClassFileTransformer setMinVersion(CfVm jdk) {
    return setMinVersion(jdk.getClassfileVersion());
  }

  public ClassFileTransformer setMinVersion(int minVersion) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            super.visit(
                Integer.max(version, minVersion), access, name, signature, superName, interfaces);
          }
        });
  }

  public ClassFileTransformer setSourceFile(String sourceFile) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visitSource(String source, String debug) {
            super.visitSource(sourceFile, debug);
          }
        });
  }

  public ClassFileTransformer setSuper(String superDescriptor) {
    assert DescriptorUtils.isClassDescriptor(superDescriptor);
    String newSuperName = getBinaryNameFromDescriptor(superDescriptor);
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            super.visit(version, access, name, signature, newSuperName, interfaces);
          }
        });
  }

  public ClassFileTransformer setAccessFlags(Consumer<ClassAccessFlags> fn) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            ClassAccessFlags flags = ClassAccessFlags.fromCfAccessFlags(access);
            fn.accept(flags);
            super.visit(
                version, flags.getAsCfAccessFlags(), name, signature, superName, interfaces);
          }
        });
  }

  public ClassFileTransformer setGenericSignature(String newGenericSignature) {
    return setGenericSignature(signature -> newGenericSignature);
  }

  public ClassFileTransformer setGenericSignature(Function<String, String> newGenericSignature) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            super.visit(
                version, access, name, newGenericSignature.apply(signature), superName, interfaces);
          }
        });
  }

  public ClassFileTransformer setNest(Class<?> host, Class<?>... members) {
    assert !Arrays.asList(members).contains(host);
    return setMinVersion(CfVm.JDK11)
        .addClassTransformer(
            new ClassTransformer() {

              final String hostName = DescriptorUtils.getBinaryNameFromJavaType(host.getTypeName());

              final List<String> memberNames =
                  Arrays.stream(members)
                      .map(m -> DescriptorUtils.getBinaryNameFromJavaType(m.getTypeName()))
                      .collect(Collectors.toList());

              String className;

              @Override
              public void visit(
                  int version,
                  int access,
                  String name,
                  String signature,
                  String superName,
                  String[] interfaces) {
                super.visit(version, access, name, signature, superName, interfaces);
                className = name;
              }

              @Override
              public void visitNestHost(String nestHost) {
                // Ignore/remove existing nest information.
              }

              @Override
              public void visitNestMember(String nestMember) {
                // Ignore/remove existing nest information.
              }

              @Override
              public void visitEnd() {
                if (className.equals(hostName)) {
                  for (String memberName : memberNames) {
                    super.visitNestMember(memberName);
                  }
                } else {
                  assert memberNames.contains(className);
                  super.visitNestHost(hostName);
                }
                super.visitEnd();
              }
            });
  }

  public ClassFileTransformer unsetAbstract() {
    return setAccessFlags(ClassAccessFlags::unsetAbstract);
  }

  public ClassFileTransformer setAnnotation() {
    return setAccessFlags(
        accessFlags -> {
          assert accessFlags.isAbstract();
          assert accessFlags.isInterface();
          accessFlags.setAnnotation();
        });
  }

  public ClassFileTransformer setBridge(Method method) {
    return setAccessFlags(method, MethodAccessFlags::setBridge);
  }

  public ClassFileTransformer setPrivate(Field field) {
    return setAccessFlags(
        field,
        accessFlags -> {
          accessFlags.setPrivate();
          accessFlags.unsetProtected();
          accessFlags.unsetPublic();
        });
  }

  public ClassFileTransformer setPublic(Method method) {
    return setAccessFlags(
        method,
        accessFlags -> {
          accessFlags.unsetPrivate();
          accessFlags.unsetProtected();
          accessFlags.setPublic();
        });
  }

  public ClassFileTransformer setPrivate(Method method) {
    return setAccessFlags(
        method,
        accessFlags -> {
          accessFlags.unsetPublic();
          accessFlags.unsetProtected();
          accessFlags.setPrivate();
        });
  }

  public ClassFileTransformer setSynthetic(Method method) {
    return setAccessFlags(method, AccessFlags::setSynthetic);
  }

  public ClassFileTransformer setAccessFlags(
      Constructor<?> constructor, Consumer<MethodAccessFlags> setter) {
    return setAccessFlags(Reference.methodFromMethod(constructor), setter);
  }

  public ClassFileTransformer setAccessFlags(Field field, Consumer<FieldAccessFlags> setter) {
    return setAccessFlags(Reference.fieldFromField(field), setter);
  }

  public ClassFileTransformer setAccessFlags(Method method, Consumer<MethodAccessFlags> setter) {
    return setAccessFlags(Reference.methodFromMethod(method), setter);
  }

  private ClassFileTransformer setAccessFlags(
      FieldReference fieldReference, Consumer<FieldAccessFlags> setter) {
    return addClassTransformer(
        new ClassTransformer() {

          @Override
          public FieldVisitor visitField(
              int access, String name, String descriptor, String signature, Object value) {
            FieldAccessFlags accessFlags = FieldAccessFlags.fromCfAccessFlags(access);
            if (name.equals(fieldReference.getFieldName())
                && descriptor.equals(fieldReference.getFieldType().getDescriptor())) {
              setter.accept(accessFlags);
            }
            return super.visitField(
                accessFlags.getAsCfAccessFlags(), name, descriptor, signature, value);
          }
        });
  }

  private ClassFileTransformer setAccessFlags(
      MethodReference methodReference, Consumer<MethodAccessFlags> setter) {
    return addClassTransformer(
        new ClassTransformer() {

          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            boolean isConstructor =
                name.equals(Constants.INSTANCE_INITIALIZER_NAME)
                    || name.equals(Constants.CLASS_INITIALIZER_NAME);
            MethodAccessFlags accessFlags =
                MethodAccessFlags.fromCfAccessFlags(access, isConstructor);
            if (name.equals(methodReference.getMethodName())
                && descriptor.equals(methodReference.getMethodDescriptor())) {
              setter.accept(accessFlags);
            }
            return super.visitMethod(
                accessFlags.getAsCfAccessFlags(), name, descriptor, signature, exceptions);
          }
        });
  }

  @FunctionalInterface
  public interface MethodPredicate {
    boolean test(int access, String name, String descriptor, String signature, String[] exceptions);

    static MethodPredicate all() {
      return (access, name, descriptor, signature, exceptions) -> true;
    }

    static MethodPredicate onName(String name) {
      return (access, otherName, descriptor, signature, exceptions) -> name.equals(otherName);
    }

    static boolean testContext(MethodPredicate predicate, MethodContext context) {
      MethodReference reference = context.getReference();
      return predicate.test(
          context.accessFlags,
          reference.getMethodName(),
          reference.getMethodDescriptor(),
          null,
          null);
    }
  }

  @FunctionalInterface
  public interface FieldPredicate {
    boolean test(int access, String name, String descriptor, String signature, Object value);

    static FieldPredicate all() {
      return (access, name, descriptor, signature, value) -> true;
    }

    static FieldPredicate onNameAndSignature(String name, String descriptor) {
      return (access, otherName, otherDescriptor, signature, value) ->
          name.equals(otherName) && descriptor.equals(otherDescriptor);
    }

    static FieldPredicate onName(String name) {
      return (access, otherName, descriptor, signature, value) -> name.equals(otherName);
    }
  }

  @FunctionalInterface
  public interface FieldSignaturePredicate {
    boolean test(String name, String typeDescriptor);
  }

  @FunctionalInterface
  public interface InnerClassPredicate {
    boolean test(String name, String outerName, String innerName, int access);

    static InnerClassPredicate always() {
      return (name, outerName, innerName, access) -> true;
    }

    static InnerClassPredicate onName(String name) {
      return (otherName, outerName, innerName, access) -> Objects.equals(name, otherName);
    }
  }

  public ClassFileTransformer removeInnerClasses() {
    return removeInnerClasses(always());
  }

  public ClassFileTransformer removeInnerClasses(InnerClassPredicate predicate) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (!predicate.test(name, outerName, innerName, access)) {
              super.visitInnerClass(name, outerName, innerName, access);
            }
          }
        });
  }

  public ClassFileTransformer rewriteEnclosingMethod(
      String newOwner, String newName, String newDescriptor) {
    return addClassTransformer(
        new ClassTransformer() {

          @Override
          public void visitOuterClass(String owner, String name, String descriptor) {
            super.visitOuterClass(newOwner, newName, newDescriptor);
          }
        });
  }

  public ClassFileTransformer removeMethods(MethodPredicate predicate) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            return predicate.test(access, name, descriptor, signature, exceptions)
                ? null
                : super.visitMethod(access, name, descriptor, signature, exceptions);
          }
        });
  }

  public ClassFileTransformer removeMethodsWithName(String nameToRemove) {
    return removeMethods(
        (access, name, descriptor, signature, exceptions) -> name.equals(nameToRemove));
  }

  public ClassFileTransformer renameMethod(MethodPredicate predicate, String newName) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            if (predicate.test(access, name, descriptor, signature, exceptions)) {
              return super.visitMethod(access, newName, descriptor, signature, exceptions);
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
          }
        });
  }

  public ClassFileTransformer setFieldType(FieldPredicate predicate, String newFieldType) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public FieldVisitor visitField(
              int access, String name, String descriptor, String signature, Object value) {
            if (predicate.test(access, name, descriptor, signature, value)) {
              String newDescriptor = DescriptorUtils.javaTypeToDescriptor(newFieldType);
              return super.visitField(access, name, newDescriptor, signature, value);
            }
            return super.visitField(access, name, descriptor, signature, value);
          }
        });
  }

  public ClassFileTransformer setReturnType(MethodPredicate predicate, String newReturnType) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            if (predicate.test(access, name, descriptor, signature, exceptions)) {
              String oldDescriptorExcludingReturnType =
                  descriptor.substring(0, descriptor.lastIndexOf(')') + 1);
              String newDescriptor =
                  oldDescriptorExcludingReturnType
                      + DescriptorUtils.javaTypeToDescriptor(newReturnType);
              return super.visitMethod(access, name, newDescriptor, signature, exceptions);
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
          }
        });
  }

  public ClassFileTransformer setGenericSignature(MethodPredicate predicate, String newSignature) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            return predicate.test(access, name, descriptor, signature, exceptions)
                ? super.visitMethod(access, name, descriptor, newSignature, exceptions)
                : super.visitMethod(access, name, descriptor, signature, exceptions);
          }
        });
  }

  public ClassFileTransformer setGenericSignature(
      MethodPredicate predicate, Function<String, String> newSignature) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            return predicate.test(access, name, descriptor, signature, exceptions)
                ? super.visitMethod(
                    access, name, descriptor, newSignature.apply(signature), exceptions)
                : super.visitMethod(access, name, descriptor, signature, exceptions);
          }
        });
  }

  public ClassFileTransformer removeFields(FieldPredicate predicate) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public FieldVisitor visitField(
              int access, String name, String descriptor, String signature, Object value) {
            return predicate.test(access, name, descriptor, signature, value)
                ? null
                : super.visitField(access, name, descriptor, signature, value);
          }
        });
  }

  public ClassFileTransformer remapField(FieldSignaturePredicate predicate, String newName) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitFieldInsn(
              final int opcode, final String owner, final String name, final String descriptor) {
            if (predicate.test(name, descriptor)) {
              super.visitFieldInsn(opcode, owner, newName, descriptor);
            } else {
              super.visitFieldInsn(opcode, owner, name, descriptor);
            }
          }
        });
  }

  public ClassFileTransformer renameField(FieldPredicate predicate, String newName) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public FieldVisitor visitField(
              int access, String name, String descriptor, String signature, Object value) {
            if (predicate.test(access, name, descriptor, signature, value)) {
              return super.visitField(access, newName, descriptor, signature, value);
            } else {
              return super.visitField(access, name, descriptor, signature, value);
            }
          }
        });
  }

  public ClassFileTransformer renameAndRemapField(String oldName, String newName) {
    FieldSignaturePredicate matchPredicate = (name, signature) -> oldName.equals(name);
    remapField(matchPredicate, newName);
    return renameField(FieldPredicate.onName(oldName), newName);
  }

  public ClassFileTransformer setGenericSignature(FieldPredicate predicate, String newSignature) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public FieldVisitor visitField(
              int access, String name, String descriptor, String signature, Object value) {
            if (predicate.test(access, name, descriptor, signature, value)) {
              return super.visitField(access, name, descriptor, newSignature, value);
            }
            return super.visitField(access, name, descriptor, signature, value);
          }
        });
  }

  /** Abstraction of the MethodVisitor.visitInvokeDynamicInsn method with its sub visitor. */
  @FunctionalInterface
  public interface InvokeDynamicInsnTransform {
    void visitInvokeDynamicInsn(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        List<Object> bootstrapMethodArguments,
        MethodVisitor visitor);
  }

  /** Abstraction of the MethodVisitor.visitFieldInsn method with its sub visitor. */
  @FunctionalInterface
  public interface FieldInsnTransform {
    void visitFieldInsn(
        int opcode, String owner, String name, String descriptor, MethodVisitor visitor);
  }

  /** Abstraction of the MethodVisitor.visitMethodInsn method with its sub visitor. */
  @FunctionalInterface
  public interface MethodInsnTransform {
    void visitMethodInsn(
        int opcode,
        String owner,
        String name,
        String descriptor,
        boolean isInterface,
        MethodVisitor visitor);
  }

  /** Abstraction of the MethodVisitor.visitTypeInsn method with its sub visitor. */
  @FunctionalInterface
  public interface TypeInsnTransform {
    void visitTypeInsn(int opcode, String type, MethodVisitor visitor);
  }

  /** Abstraction of the MethodVisitor.visitLdcInsn method with its sub visitor. */
  @FunctionalInterface
  public interface LdcInsnTransform {
    void visitLdcInsn(Object value, MethodVisitor visitor);
  }

  /** Abstraction of the MethodVisitor.visitTryCatchBlock method with its sub visitor. */
  @FunctionalInterface
  public interface TryCatchBlockTransform {
    void visitTryCatchBlock(
        Label start, Label end, Label handler, String type, MethodVisitor visitor);
  }

  public ClassFileTransformer replaceAnnotationDescriptor(
      String oldDescriptor, String newDescriptor) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return super.visitAnnotation(
                descriptor.equals(oldDescriptor) ? newDescriptor : descriptor, visible);
          }
        });
  }

  public ClassFileTransformer replaceClassDescriptorInMembers(
      String oldDescriptor, String newDescriptor) {
    return addClassTransformer(
        new ClassTransformer() {
          @Override
          public FieldVisitor visitField(
              int access, String name, String descriptor, String signature, Object value) {
            return super.visitField(
                access,
                name,
                replaceAll(descriptor, oldDescriptor, newDescriptor),
                signature,
                value);
          }

          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            return super.visitMethod(
                access,
                name,
                replaceAll(descriptor, oldDescriptor, newDescriptor),
                signature,
                exceptions);
          }
        });
  }

  public ClassFileTransformer replaceClassDescriptorInAnnotationDefault(
      String oldDescriptor, String newDescriptor) {
    return addMethodTransformer(
        new MethodTransformer() {

          @Override
          public AnnotationVisitor visitAnnotationDefault() {
            return new AnnotationVisitor(ASM9, super.visitAnnotationDefault()) {
              @Override
              public void visit(String name, Object value) {
                super.visit(name, value);
              }

              @Override
              public void visitEnum(String name, String descriptor, String value) {
                super.visitEnum(
                    name, descriptor.equals(oldDescriptor) ? newDescriptor : descriptor, value);
              }
            };
          }
        });
  }

  public ClassFileTransformer replaceClassDescriptorInMethodInstructions(
      String oldDescriptor, String newDescriptor) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(
                opcode,
                rewriteASMInternalTypeName(owner),
                name,
                replaceAll(descriptor, oldDescriptor, newDescriptor));
          }

          @Override
          public void visitLdcInsn(Object value) {
            if (value instanceof Type) {
              Type type = (Type) value;
              super.visitLdcInsn(
                  Type.getType(replaceAll(type.getDescriptor(), oldDescriptor, newDescriptor)));
            } else {
              super.visitLdcInsn(value);
            }
          }

          @Override
          public void visitMethodInsn(
              int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(
                opcode,
                rewriteASMInternalTypeName(owner),
                name,
                replaceAll(descriptor, oldDescriptor, newDescriptor),
                isInterface);
          }

          @Override
          public void visitTypeInsn(int opcode, String type) {
            super.visitTypeInsn(opcode, rewriteASMInternalTypeName(type));
          }

          private String rewriteASMInternalTypeName(String type) {
            return Type.getType(
                    replaceAll(
                        Type.getObjectType(type).getDescriptor(), oldDescriptor, newDescriptor))
                .getInternalName();
          }
        });
  }

  @FunctionalInterface
  private interface VisitInvokeDynamicInsnCallback {
    void visitInvokeDynamicInsn(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments);
  }

  private MethodVisitor redirectVisitInvokeDynamicInsn(
      MethodVisitor visitor, VisitInvokeDynamicInsnCallback callback) {
    return new MethodVisitor(ASM7, visitor) {
      @Override
      public void visitInvokeDynamicInsn(
          String name,
          String descriptor,
          Handle bootstrapMethodHandle,
          Object... bootstrapMethodArguments) {
        callback.visitInvokeDynamicInsn(
            name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
      }
    };
  }

  public ClassFileTransformer removeLineNumberTable(MethodPredicate predicate) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitLineNumber(int line, Label start) {
            if (MethodPredicate.testContext(predicate, getContext())) {
              // Empty to ensure no line numbers are added to the code.
            } else {
              super.visitLineNumber(line, start);
            }
          }
        });
  }

  public ClassFileTransformer transformInvokeDynamicInsnInMethod(
      String methodName, InvokeDynamicInsnTransform transform) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitInvokeDynamicInsn(
              String name,
              String descriptor,
              Handle bootstrapMethodHandle,
              Object... bootstrapMethodArguments) {
            if (getContext().method.getMethodName().equals(methodName)) {
              transform.visitInvokeDynamicInsn(
                  name,
                  descriptor,
                  bootstrapMethodHandle,
                  Arrays.asList(bootstrapMethodArguments),
                  redirectVisitInvokeDynamicInsn(this, super::visitInvokeDynamicInsn));
            } else {
              super.visitInvokeDynamicInsn(
                  name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            }
          }
        });
  }

  public ClassFileTransformer transformConstStringToConstantDynamic(
      String constantName,
      Class<?> bootstrapMethodHolder,
      String bootstrapMethodName,
      boolean isInterfaceInvoke,
      String name,
      Class<?> type) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitLdcInsn(Object value) {
            String bootstrapMethodSignature =
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;";
            if (value instanceof String && value.equals(constantName)) {
              super.visitLdcInsn(
                  new ConstantDynamic(
                      name,
                      Reference.classFromClass(type).getDescriptor(),
                      new Handle(
                          Opcodes.H_INVOKESTATIC,
                          DescriptorUtils.getClassBinaryName(bootstrapMethodHolder),
                          bootstrapMethodName,
                          bootstrapMethodSignature,
                          isInterfaceInvoke),
                      new Object[] {}));
            } else {
              super.visitLdcInsn(value);
            }
          }
        });
  }

  @FunctionalInterface
  private interface VisitFieldInsnCallback {
    void visitFieldInsn(int opcode, String owner, String name, String descriptor);
  }

  private MethodVisitor redirectVisitFieldInsn(
      MethodVisitor visitor, VisitFieldInsnCallback callback) {
    return new MethodVisitor(ASM7, visitor) {
      @Override
      public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        callback.visitFieldInsn(opcode, owner, name, descriptor);
      }
    };
  }

  public ClassFileTransformer transformFieldInsnInMethod(
      String methodName, FieldInsnTransform transform) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (getContext().method.getMethodName().equals(methodName)) {
              transform.visitFieldInsn(
                  opcode,
                  owner,
                  name,
                  descriptor,
                  redirectVisitFieldInsn(this, super::visitFieldInsn));
            } else {
              super.visitMethodInsn(opcode, owner, name, descriptor);
            }
          }
        });
  }

  @FunctionalInterface
  private interface VisitMethodInsnCallback {
    void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface);
  }

  private MethodVisitor redirectVisitMethodInsn(
      MethodVisitor visitor, VisitMethodInsnCallback callback) {
    return new MethodVisitor(ASM7, visitor) {
      @Override
      public void visitMethodInsn(
          int opcode, String owner, String name, String descriptor, boolean isInterface) {
        callback.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
    };
  }

  public ClassFileTransformer transformMethodInsnInMethod(
      String methodName, MethodInsnTransform transform) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitMethodInsn(
              int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (getContext().method.getMethodName().equals(methodName)) {
              transform.visitMethodInsn(
                  opcode,
                  owner,
                  name,
                  descriptor,
                  isInterface,
                  redirectVisitMethodInsn(this, super::visitMethodInsn));
            } else {
              super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
          }
        });
  }

  @FunctionalInterface
  private interface VisitTypeInsnCallback {
    void visitTypeInsn(int opcode, String type);
  }

  private MethodVisitor redirectVisitTypeInsn(
      MethodVisitor visitor, VisitTypeInsnCallback callback) {
    return new MethodVisitor(ASM7, visitor) {
      @Override
      public void visitTypeInsn(int opcode, String type) {
        callback.visitTypeInsn(opcode, type);
      }
    };
  }

  public ClassFileTransformer transformTypeInsnInMethod(
      String methodName, TypeInsnTransform transform) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitTypeInsn(int opcode, String type) {
            if (getContext().method.getMethodName().equals(methodName)) {
              transform.visitTypeInsn(
                  opcode, type, redirectVisitTypeInsn(this, super::visitTypeInsn));
            } else {
              super.visitTypeInsn(opcode, type);
            }
          }
        });
  }

  @FunctionalInterface
  private interface VisitTryCatchBlockCallback {
    void visitTryCatchBlock(Label start, Label end, Label handler, String type);
  }

  private MethodVisitor redirectVisitTryCatchBlock(
      MethodVisitor visitor, VisitTryCatchBlockCallback callback) {
    return new MethodVisitor(ASM7, visitor) {
      @Override
      public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        callback.visitTryCatchBlock(start, end, handler, type);
      }
    };
  }

  public ClassFileTransformer transformTryCatchBlock(
      String methodName, TryCatchBlockTransform transform) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (getContext().method.getMethodName().equals(methodName)) {
              transform.visitTryCatchBlock(
                  start,
                  end,
                  handler,
                  type,
                  redirectVisitTryCatchBlock(this, super::visitTryCatchBlock));
            } else {
              super.visitTryCatchBlock(start, end, handler, type);
            }
          }
        });
  }

  @FunctionalInterface
  private interface VisitLdcInsnCallback {
    void visitLdcInsn(Object value);
  }

  private MethodVisitor redirectVisitLdcInsn(MethodVisitor visitor, VisitLdcInsnCallback callback) {
    return new MethodVisitor(ASM7, visitor) {
      @Override
      public void visitLdcInsn(Object value) {
        callback.visitLdcInsn(value);
      }
    };
  }

  public ClassFileTransformer transformLdcInsnInMethod(
      String methodName, LdcInsnTransform transform) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitLdcInsn(Object value) {
            if (getContext().method.getMethodName().equals(methodName)) {
              transform.visitLdcInsn(value, redirectVisitLdcInsn(this, super::visitLdcInsn));
            } else {
              super.visitLdcInsn(value);
            }
          }
        });
  }

  public ClassFileTransformer stripFrames(String methodName) {
    return addMethodTransformer(
        new MethodTransformer() {

          @Override
          public void visitFrame(
              int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if (!getContext().method.getMethodName().equals(methodName)) {
              super.visitFrame(type, numLocal, local, numStack, stack);
            }
          }
        });
  }

  public ClassFileTransformer setMaxStackHeight(MethodPredicate predicate, int newMaxStack) {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(
                MethodPredicate.testContext(predicate, getContext()) ? newMaxStack : maxStack,
                maxLocals);
          }
        });
  }

  public ClassFileTransformer computeMaxs() {
    return addMethodTransformer(
        new MethodTransformer() {
          @Override
          public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack, maxLocals);
          }
        });
  }
}
