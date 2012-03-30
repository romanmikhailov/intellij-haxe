package com.intellij.plugins.haxe.ide;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.DaemonBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator;
import com.intellij.ide.util.DefaultPsiElementCellRenderer;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.IconLoader;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeComponentType;
import com.intellij.plugins.haxe.ide.index.HaxeInheritanceIndex;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeLineMarkerProvider implements LineMarkerProvider {
  protected static final Icon OVERRIDING_METHOD_ICON = IconLoader.getIcon("/gutter/overridingMethod.png");
  protected static final Icon IMPLEMENTING_METHOD_ICON = IconLoader.getIcon("/gutter/implementingMethod.png");

  protected static final Icon OVERRIDEN_METHOD_MARKER_RENDERER = IconLoader.getIcon("/gutter/overridenMethod.png");
  protected static final Icon IMPLEMENTED_METHOD_MARKER_RENDERER = IconLoader.getIcon("/gutter/implementedMethod.png");

  @Override
  public LineMarkerInfo getLineMarkerInfo(PsiElement element) {
    return null;
  }

  @Override
  public void collectSlowLineMarkers(List<PsiElement> elements, Collection<LineMarkerInfo> result) {
    for (PsiElement element : elements) {
      if (element instanceof HaxeClass) {
        collectClassMarkers(result, (HaxeClass)element);
      }
    }
  }

  private static void collectClassMarkers(Collection<LineMarkerInfo> result, HaxeClass haxeClass) {
    final List<HaxeClass> supers = HaxeResolveUtil.resolveClasses(haxeClass.getExtendsList());
    supers.addAll(HaxeResolveUtil.resolveClasses(haxeClass.getImplementsList()));
    final List<HaxeNamedComponent> superItems = HaxeResolveUtil.findNamedSubComponents(supers.toArray(new HaxeClass[supers.size()]));

    final List<HaxeClass> subClasses = HaxeInheritanceIndex.getItemsByQName(haxeClass);
    final List<HaxeNamedComponent> subItems = new ArrayList<HaxeNamedComponent>();
    for (HaxeClass subClass : subClasses) {
      subItems.addAll(HaxeResolveUtil.getNamedSubComponents(subClass));
    }

    for (HaxeNamedComponent haxeNamedComponent : HaxeResolveUtil.getNamedSubComponents(haxeClass)) {
      final HaxeComponentType type = HaxeComponentType.typeOf(haxeNamedComponent);
      if (type == HaxeComponentType.METHOD) {
        final HaxeComponentWithDeclarationList methodDeclaration = (HaxeComponentWithDeclarationList)haxeNamedComponent;
        LineMarkerInfo item = tryCreateOverrideMarker(methodDeclaration, superItems);
        if (item != null) {
          result.add(item);
        }
        final boolean isInterface = HaxeComponentType.typeOf(haxeClass) == HaxeComponentType.INTERFACE;
        item = tryCreateImplementationMarker(methodDeclaration, subItems, isInterface);
        if (item != null) {
          result.add(item);
        }
      }
    }

    if (!subClasses.isEmpty()) {
      result.add(createImplementationMarker(haxeClass, subClasses));
    }
  }

  @Nullable
  private static LineMarkerInfo tryCreateOverrideMarker(final HaxeComponentWithDeclarationList componentWithDeclarationList,
                                                        List<HaxeNamedComponent> superItems) {
    final String methodName = componentWithDeclarationList.getName();
    if (methodName == null || !componentWithDeclarationList.isPublic()) {
      return null;
    }
    final List<HaxeNamedComponent> filteredSuperItems = ContainerUtil.filter(superItems, new Condition<HaxeNamedComponent>() {
      @Override
      public boolean value(HaxeNamedComponent component) {
        return methodName.equals(component.getName());
      }
    });
    if (filteredSuperItems.isEmpty()) {
      return null;
    }
    final PsiElement element = componentWithDeclarationList.getComponentName();
    final boolean overrides =
      HaxeResolveUtil.getDeclarationTypes(componentWithDeclarationList.getDeclarationAttributeList()).contains(HaxeTokenTypes.KOVERRIDE);
    final Icon icon = overrides ? OVERRIDING_METHOD_ICON : IMPLEMENTING_METHOD_ICON;
    assert element != null;
    return new LineMarkerInfo<PsiElement>(
      element,
      element.getTextRange(),
      icon,
      Pass.UPDATE_ALL,
      new Function<PsiElement, String>() {
        @Override
        public String fun(PsiElement element) {
          final HaxeClass superHaxeClass = PsiTreeUtil.getParentOfType(componentWithDeclarationList, HaxeClass.class);
          if (superHaxeClass == null) return "null";
          if (overrides) {
            return HaxeBundle.message("overrides.method.in", componentWithDeclarationList.getName(), superHaxeClass.getQualifiedName());
          }
          return HaxeBundle.message("implements.method.in", componentWithDeclarationList.getName(), superHaxeClass.getQualifiedName());
        }
      },
      new GutterIconNavigationHandler<PsiElement>() {
        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
          PsiElementListNavigator.openTargets(
            e,
            HaxeResolveUtil.getComponentNames(filteredSuperItems).toArray(new NavigatablePsiElement[filteredSuperItems.size()]),
            DaemonBundle.message("navigation.title.super.method", componentWithDeclarationList.getName()),
            new DefaultPsiElementCellRenderer());
        }
      },
      GutterIconRenderer.Alignment.LEFT
    );
  }

  @Nullable
  private static LineMarkerInfo tryCreateImplementationMarker(final HaxeComponentWithDeclarationList componentWithDeclarationList,
                                                              List<HaxeNamedComponent> subItems,
                                                              final boolean isInterface) {
    final PsiElement componentName = componentWithDeclarationList.getComponentName();
    final String methodName = componentWithDeclarationList.getName();
    if (methodName == null || !componentWithDeclarationList.isPublic()) {
      return null;
    }
    final List<HaxeNamedComponent> filteredSubItems = ContainerUtil.filter(subItems, new Condition<HaxeNamedComponent>() {
      @Override
      public boolean value(HaxeNamedComponent component) {
        return methodName.equals(component.getName());
      }
    });
    if (filteredSubItems.isEmpty() || componentName == null) {
      return null;
    }
    return new LineMarkerInfo<PsiElement>(
      componentName,
      componentName.getTextRange(),
      isInterface ? IMPLEMENTED_METHOD_MARKER_RENDERER : OVERRIDEN_METHOD_MARKER_RENDERER,
      Pass.UPDATE_ALL,
      new Function<PsiElement, String>() {
        @Override
        public String fun(PsiElement element) {
          return isInterface
                 ? DaemonBundle.message("method.is.implemented.too.many")
                 : DaemonBundle.message("method.is.overridden.too.many");
        }
      },
      new GutterIconNavigationHandler<PsiElement>() {
        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
          PsiElementListNavigator.openTargets(
            e, HaxeResolveUtil.getComponentNames(filteredSubItems).toArray(new NavigatablePsiElement[filteredSubItems.size()]),
            isInterface ?
            DaemonBundle.message("navigation.title.implementation.method", componentWithDeclarationList.getName(), filteredSubItems.size())
                        :
            DaemonBundle.message("navigation.title.overrider.method", componentWithDeclarationList.getName(), filteredSubItems.size()),
            new DefaultPsiElementCellRenderer()
          );
        }
      },
      GutterIconRenderer.Alignment.RIGHT
    );
  }

  private static LineMarkerInfo createImplementationMarker(final HaxeClass componentWithDeclarationList,
                                                           final List<HaxeClass> items) {
    final HaxeComponentName componentName = componentWithDeclarationList.getComponentName();
    return new LineMarkerInfo<PsiElement>(
      componentName,
      componentName.getTextRange(),
      componentWithDeclarationList instanceof HaxeInterfaceDeclaration
      ? IMPLEMENTED_METHOD_MARKER_RENDERER
      : OVERRIDEN_METHOD_MARKER_RENDERER,
      Pass.UPDATE_ALL,
      new Function<PsiElement, String>() {
        @Override
        public String fun(PsiElement element) {
          return DaemonBundle.message("method.is.implemented.too.many");
        }
      },
      new GutterIconNavigationHandler<PsiElement>() {
        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
          PsiElementListNavigator.openTargets(
            e, HaxeResolveUtil.getComponentNames(items).toArray(new NavigatablePsiElement[items.size()]),
            DaemonBundle.message("navigation.title.subclass", componentWithDeclarationList.getName(), items.size()),
            new DefaultPsiElementCellRenderer()
          );
        }
      },
      GutterIconRenderer.Alignment.RIGHT
    );
  }
}
