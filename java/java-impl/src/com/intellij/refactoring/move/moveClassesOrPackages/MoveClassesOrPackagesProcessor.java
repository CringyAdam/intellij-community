package com.intellij.refactoring.move.moveClassesOrPackages;

import com.intellij.codeInsight.ChangeContextUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PackageScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.MoveDestination;
import com.intellij.refactoring.PackageWrapper;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.move.MoveCallback;
import com.intellij.refactoring.move.MoveClassesOrPackagesCallback;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.refactoring.util.*;
import com.intellij.refactoring.util.classRefs.ClassInstanceScanner;
import com.intellij.refactoring.util.classRefs.ClassReferenceScanner;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Processor;
import com.intellij.util.VisibilityUtil;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author Jeka,dsl
 */
public class MoveClassesOrPackagesProcessor extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance(
    "#com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor");

  private final PsiElement[] myElementsToMove;
  private boolean mySearchInComments;
  private boolean mySearchInNonJavaFiles;
  private final PackageWrapper myTargetPackage;
  private final MoveCallback myMoveCallback;
  protected final MoveDestination myMoveDestination;
  protected NonCodeUsageInfo[] myNonCodeUsages;

  public MoveClassesOrPackagesProcessor(
    Project project,
    PsiElement[] elements,
    final MoveDestination moveDestination,
    boolean searchInComments,
    boolean searchInNonJavaFiles,
    MoveCallback moveCallback) {
    super(project);
    myElementsToMove = elements;
    myMoveDestination = moveDestination;
    myTargetPackage = myMoveDestination.getTargetPackage();
    mySearchInComments = searchInComments;
    mySearchInNonJavaFiles = searchInNonJavaFiles;
    myMoveCallback = moveCallback;
  }

  protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages) {
    PsiElement[] elements = new PsiElement[myElementsToMove.length];
    System.arraycopy(myElementsToMove, 0, elements, 0, myElementsToMove.length);
    return new MoveClassesOrPackagesViewDescriptor(elements, mySearchInComments, mySearchInNonJavaFiles,
                                                   MoveClassesOrPackagesUtil.getPackageName(myTargetPackage));
  }

  public boolean verifyValidPackageName() {
    String qName = myTargetPackage.getQualifiedName();
    if (StringUtil.isEmpty(qName)) {
      if (hasClasses()) {
        Messages.showMessageDialog(myProject, RefactoringBundle.message("invalid.target.package.name.default.package"), "Cannot Move", Messages.getErrorIcon());
        return false;
      }
    } else {
      PsiNameHelper helper = JavaPsiFacade.getInstance(myProject).getNameHelper();
      if (!helper.isQualifiedName(qName)) {
        Messages.showMessageDialog(myProject, RefactoringBundle.message("invalid.target.package.name.specified"), "Invalid Package Name",
                                   Messages.getErrorIcon());
        return false;
      }
    }
    return true;
  }

  private boolean hasClasses() {
    for (PsiElement element : getElements()) {
      if (element instanceof PsiClass) return true;
    }
    return false;
  }

  public boolean isSearchInComments() {
    return mySearchInComments;
  }

  public boolean isSearchInNonJavaFiles() {
    return mySearchInNonJavaFiles;
  }

  public void setSearchInComments(boolean searchInComments) {
    mySearchInComments = searchInComments;
  }

  public void setSearchInNonJavaFiles(boolean searchInNonJavaFiles) {
    mySearchInNonJavaFiles = searchInNonJavaFiles;
  }


  @NotNull
  protected UsageInfo[] findUsages() {
    List<UsageInfo> allUsages = new ArrayList<UsageInfo>();
    ArrayList<String> conflicts = new ArrayList<String>();
    for (PsiElement element : myElementsToMove) {
      String newName = getNewQName(element);
      final UsageInfo[] usages = MoveClassesOrPackagesUtil.findUsages(element, mySearchInComments,
                                                                      mySearchInNonJavaFiles, newName);
      allUsages.addAll(new ArrayList<UsageInfo>(Arrays.asList(usages)));
    }
    myMoveDestination.analyzeModuleConflicts(Arrays.asList(myElementsToMove), conflicts,
                                             allUsages.toArray(new UsageInfo[allUsages.size()]));
    final UsageInfo[] usageInfos = allUsages.toArray(new UsageInfo[allUsages.size()]);
    detectPackageLocalsMoved(usageInfos, conflicts);
    detectPackageLocalsUsed(conflicts);
    if (!conflicts.isEmpty()) {
      allUsages.add(new ConflictsUsageInfo(myElementsToMove[0], conflicts));
    }

    return UsageViewUtil.removeDuplicatedUsages(allUsages.toArray(new UsageInfo[allUsages.size()]));
  }

  public List<PsiElement> getElements() {
    return Collections.unmodifiableList(Arrays.asList(myElementsToMove));
  }

  public PackageWrapper getTargetPackage() {
    return myMoveDestination.getTargetPackage();
  }

  protected static class ConflictsUsageInfo extends UsageInfo {
    private final ArrayList<String> myConflicts;

    public ConflictsUsageInfo(PsiElement pseudoElement, ArrayList<String> conflicts) {
      super(pseudoElement);
      myConflicts = conflicts;
    }

    public ArrayList<String> getConflicts() {
      return myConflicts;
    }
  }



  protected boolean preprocessUsages(Ref<UsageInfo[]> refUsages) {
    final UsageInfo[] usages = refUsages.get();
    final ArrayList<String> conflicts = new ArrayList<String>();
    ArrayList<UsageInfo> filteredUsages = new ArrayList<UsageInfo>();
    for (UsageInfo usage : usages) {
      if (usage instanceof ConflictsUsageInfo) {
        conflicts.addAll(((ConflictsUsageInfo)usage).getConflicts());
      }
      else {
        filteredUsages.add(usage);
      }
    }

    refUsages.set(filteredUsages.toArray(new UsageInfo[filteredUsages.size()]));
    return showConflicts(conflicts);
  }

  private boolean isInsideMoved(PsiElement place) {
    for (PsiElement element : myElementsToMove) {
      if (element instanceof PsiClass) {
        if (PsiTreeUtil.isAncestor(element, place, false)) return true;
      }
    }
    return false;
  }

  private void detectPackageLocalsUsed(final ArrayList<String> conflicts) {
    PackageLocalsUsageCollector visitor = new PackageLocalsUsageCollector(myElementsToMove, myTargetPackage, conflicts);

    for (PsiElement element : myElementsToMove) {
      if (element instanceof PsiClass) {
        PsiClass aClass = (PsiClass)element;
        aClass.accept(visitor);
      }
    }
  }

  private void detectPackageLocalsMoved(final UsageInfo[] usages, final ArrayList<String> conflicts) {
//    final HashSet reportedPackageLocalUsed = new HashSet();
    final HashSet<PsiClass> movedClasses = new HashSet<PsiClass>();
    final HashMap<PsiClass,HashSet<PsiElement>> reportedClassToContainers = new HashMap<PsiClass, HashSet<PsiElement>>();
    final PackageWrapper aPackage = myTargetPackage;
    for (UsageInfo usage : usages) {
      PsiElement element = usage.getElement();
      if (element == null) continue;
      if (usage instanceof MoveRenameUsageInfo && !(usage instanceof NonCodeUsageInfo) &&
          ((MoveRenameUsageInfo)usage).getReferencedElement() instanceof PsiClass) {
        PsiClass aClass = (PsiClass)((MoveRenameUsageInfo)usage).getReferencedElement();
        if (!movedClasses.contains(aClass)) {
          movedClasses.add(aClass);
        }
        String visibility = VisibilityUtil.getVisibilityModifier(aClass.getModifierList());
        if (PsiModifier.PACKAGE_LOCAL.equals(visibility)) {
          if (PsiTreeUtil.getParentOfType(element, PsiImportStatement.class) != null) continue;
          PsiElement container = ConflictsUtil.getContainer(element);
          if (container == null) continue;
          HashSet<PsiElement> reported = reportedClassToContainers.get(aClass);
          if (reported == null) {
            reported = new HashSet<PsiElement>();
            reportedClassToContainers.put(aClass, reported);
          }

          if (!reported.contains(container)) {
            reported.add(container);
            PsiFile containingFile = element.getContainingFile();
            if (containingFile != null && !isInsideMoved(element)) {
              PsiDirectory directory = containingFile.getContainingDirectory();
              if (directory != null) {
                PsiPackage usagePackage = JavaDirectoryService.getInstance().getPackage(directory);
                if (aPackage != null && usagePackage != null && !aPackage.equalToPackage(usagePackage)) {

                  final String message = RefactoringBundle.message("a.package.local.class.0.will.no.longer.be.accessible.from.1",
                                                                   CommonRefactoringUtil.htmlEmphasize(aClass.getName()),
                                                                   RefactoringUIUtil.getDescription(
                                                                   container, true));
                  conflicts.add(message);
                }
              }
            }
          }
        }
      }
    }

    final MyClassInstanceReferenceVisitor instanceReferenceVisitor = new MyClassInstanceReferenceVisitor(conflicts);
    for (final PsiClass aClass : movedClasses) {
      String visibility = VisibilityUtil.getVisibilityModifier(aClass.getModifierList());
      if (PsiModifier.PACKAGE_LOCAL.equals(visibility)) {
        findInstancesOfPackageLocal(aClass, usages, instanceReferenceVisitor);
      }
      else {
        // public classes
        findPublicClassConflicts(aClass, instanceReferenceVisitor);
      }
    }
  }

  static class ClassMemberWrapper {
    final PsiNamedElement myElement;
    final PsiModifierListOwner myMember;

    public ClassMemberWrapper(PsiNamedElement element) {
      myElement = element;
      myMember = (PsiModifierListOwner) element;
    }

    PsiModifierListOwner getMember() {
      return myMember;
    }


    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ClassMemberWrapper)) return false;

      ClassMemberWrapper wrapper = (ClassMemberWrapper)o;

      if (myElement instanceof PsiMethod) {
        return wrapper.myElement instanceof PsiMethod &&
            MethodSignatureUtil.areSignaturesEqual((PsiMethod) myElement, (PsiMethod) wrapper.myElement);
      }


      return Comparing.equal(myElement.getName(), wrapper.myElement.getName());
    }

    public int hashCode() {
      final String name = myElement.getName();
      if (name != null) {
        return name.hashCode();
      }
      else {
        return 0;
      }
    }
  }

  private static void findPublicClassConflicts(PsiClass aClass, final MyClassInstanceReferenceVisitor instanceReferenceVisitor) {
    //noinspection MismatchedQueryAndUpdateOfCollection
    NonPublicClassMemberWrappersSet members = new NonPublicClassMemberWrappersSet();

    members.addElements(aClass.getFields());
    members.addElements(aClass.getMethods());
    members.addElements(aClass.getInnerClasses());

    final RefactoringUtil.IsDescendantOf isDescendantOf = new RefactoringUtil.IsDescendantOf(aClass);
    final PsiPackage aPackage = JavaDirectoryService.getInstance().getPackage(aClass.getContainingFile().getContainingDirectory());
    final GlobalSearchScope packageScope = aPackage == null ? aClass.getResolveScope() : PackageScope.packageScopeWithoutLibraries(aPackage, false);
    for (final ClassMemberWrapper memberWrapper : members) {
      ReferencesSearch.search(memberWrapper.getMember(), packageScope, false).forEach(new Processor<PsiReference>() {
        public boolean process(final PsiReference reference) {
          final PsiElement element = reference.getElement();
          if (element instanceof PsiReferenceExpression) {
            final PsiReferenceExpression expression = (PsiReferenceExpression)element;
            final PsiExpression qualifierExpression = expression.getQualifierExpression();
            if (qualifierExpression != null) {
              final PsiType type = qualifierExpression.getType();
              if (type != null) {
                final PsiClass resolvedTypeClass = PsiUtil.resolveClassInType(type);
                if (isDescendantOf.value(resolvedTypeClass)) {
                  instanceReferenceVisitor.visitMemberReference(memberWrapper.getMember(), expression, isDescendantOf);
                }
              }
            }
            else {
              instanceReferenceVisitor.visitMemberReference(memberWrapper.getMember(), expression, isDescendantOf);
            }
          }
          return true;
        }
      });
    }
  }

  private static void findInstancesOfPackageLocal(final PsiClass aClass,
                                           final UsageInfo[] usages,
                                           final MyClassInstanceReferenceVisitor instanceReferenceVisitor) {
    ClassReferenceScanner referenceScanner = new ClassReferenceScanner(aClass) {
      public PsiReference[] findReferences() {
        ArrayList<PsiReference> result = new ArrayList<PsiReference>();
        for (UsageInfo usage : usages) {
          if (usage instanceof MoveRenameUsageInfo && ((MoveRenameUsageInfo)usage).getReferencedElement() == aClass) {
            final PsiReference reference = usage.getReference();
            if (reference != null) {
              result.add(reference);
            }
          }
        }
        return result.toArray(new PsiReference[result.size()]);
      }
    };
    referenceScanner.processReferences(new ClassInstanceScanner(aClass, instanceReferenceVisitor));
  }


  private String getNewQName(PsiElement element) {
    final String qualifiedName = myTargetPackage.getQualifiedName();
    if (element instanceof PsiClass) {
      return qualifiedName + (qualifiedName.length() == 0 ? "" : ".") + ((PsiClass)element).getName();
    }
    else if (element instanceof PsiPackage) {
      return qualifiedName + (qualifiedName.length() == 0 ? "" : ".") + ((PsiPackage)element).getName();
    }
    else {
      LOG.assertTrue(false);
      return null;
    }
  }

  protected void refreshElements(PsiElement[] elements) {
    LOG.assertTrue(elements.length == myElementsToMove.length);
    System.arraycopy(elements, 0, myElementsToMove, 0, elements.length);
  }

  protected boolean isPreviewUsages(UsageInfo[] usages) {
    if (UsageViewUtil.hasNonCodeUsages(usages)) {
      WindowManager.getInstance().getStatusBar(myProject).setInfo(
        RefactoringBundle.message("occurrences.found.in.comments.strings.and.non.java.files"));
      return true;
    }
    else {
      return super.isPreviewUsages(usages);
    }
  }

  protected void performRefactoring(UsageInfo[] usages) {
    // If files are being moved then I need to collect some information to delete these
    // filese from CVS. I need to know all common parents of the moved files and releative
    // paths.

    // Move files with correction of references.

    try {
      Map<PsiElement, PsiElement> oldToNewElementsMapping = new HashMap<PsiElement, PsiElement>();
      for (int idx = 0; idx < myElementsToMove.length; idx++) {
        PsiElement element = myElementsToMove[idx];
        final RefactoringElementListener elementListener = getTransaction().getElementListener(element);
        if (element instanceof PsiPackage) {

          final PsiPackage newElement =
          MoveClassesOrPackagesUtil.doMovePackage((PsiPackage)element, myMoveDestination);
          oldToNewElementsMapping.put(element, newElement);
          element = newElement;
        }
        else if (element instanceof PsiClass) {
          ChangeContextUtil.encodeContextInfo(element, true);
          final PsiClass newElement = MoveClassesOrPackagesUtil.doMoveClass((PsiClass)element, myMoveDestination.getTargetDirectory(element.getContainingFile()));
          oldToNewElementsMapping.put(element, newElement);
          element = newElement;
        } else {
          LOG.error("Unexpected element to move: " + element);
        }
        elementListener.elementMoved(element);
        myElementsToMove[idx] = element;
      }

      for (PsiElement element : myElementsToMove) {
        if (element instanceof PsiClass) {
          ChangeContextUtil.decodeContextInfo(element, null, null);
        }
      }

      myNonCodeUsages = retargetUsages(usages, oldToNewElementsMapping);
    }
    catch (IncorrectOperationException e) {
      myNonCodeUsages = new NonCodeUsageInfo[0];
      RefactoringUIUtil.processIncorrectOperation(myProject, e);
    }
  }

  public static NonCodeUsageInfo[] retargetUsages(final UsageInfo[] usages, final Map<PsiElement, PsiElement> oldToNewElementsMapping)
    throws IncorrectOperationException {
    List<NonCodeUsageInfo> nonCodeUsages = new ArrayList<NonCodeUsageInfo>();
    for (UsageInfo usage : usages) {
      if (usage instanceof NonCodeUsageInfo) {
        nonCodeUsages.add((NonCodeUsageInfo)usage);
      }
      else if (usage instanceof MoveRenameUsageInfo) {
        final MoveRenameUsageInfo moveRenameUsage = (MoveRenameUsageInfo)usage;
        final PsiElement oldElement = moveRenameUsage.getReferencedElement();
        final PsiElement newElement = oldToNewElementsMapping.get(oldElement);
        LOG.assertTrue(newElement != null);
        final PsiReference reference = moveRenameUsage.getReference();
        if (reference != null) {
          reference.bindToElement(newElement);
        }
      }
    }
    return nonCodeUsages.toArray(new NonCodeUsageInfo[nonCodeUsages.size()]);
  }

  protected void performPsiSpoilingRefactoring() {
    RenameUtil.renameNonCodeUsages(myProject, myNonCodeUsages);
    if (myMoveCallback != null) {
      if (myMoveCallback instanceof MoveClassesOrPackagesCallback) {
        ((MoveClassesOrPackagesCallback) myMoveCallback).classesOrPackagesMoved(myMoveDestination);
      }
      myMoveCallback.refactoringCompleted();
    }
  }

  protected String getCommandName() {
    String elements = RefactoringUIUtil.calculatePsiElementDescriptionList(myElementsToMove);
    String target = myTargetPackage.getQualifiedName();
    return RefactoringBundle.message("move.classes.command", elements, target);
  }

  private class MyClassInstanceReferenceVisitor implements ClassInstanceScanner.ClassInstanceReferenceVisitor {
    private final ArrayList<String> myConflicts;
    private final HashMap<PsiModifierListOwner,HashSet<PsiElement>> myReportedElementToContainer = new HashMap<PsiModifierListOwner, HashSet<PsiElement>>();
    private final HashMap<PsiClass, RefactoringUtil.IsDescendantOf> myIsDescendantOfCache = new HashMap<PsiClass,RefactoringUtil.IsDescendantOf>();

    public MyClassInstanceReferenceVisitor(ArrayList<String> conflicts) {
      myConflicts = conflicts;
    }

    public void visitQualifier(PsiReferenceExpression qualified,
                               PsiExpression instanceRef,
                               PsiElement referencedInstance) {
      PsiElement resolved = qualified.resolve();

      if (resolved instanceof PsiMember) {
        final PsiMember member = (PsiMember)resolved;
        final PsiClass containingClass = member.getContainingClass();
        RefactoringUtil.IsDescendantOf isDescendantOf = myIsDescendantOfCache.get(containingClass);
        if (isDescendantOf == null) {
          isDescendantOf = new RefactoringUtil.IsDescendantOf(containingClass);
          myIsDescendantOfCache.put(containingClass, isDescendantOf);
        }
        visitMemberReference(member, qualified, isDescendantOf);
      }
    }

    private void visitMemberReference(final PsiModifierListOwner member, PsiReferenceExpression qualified, final RefactoringUtil.IsDescendantOf descendantOf) {
      if (member.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)) {
        visitPackageLocalMemberReference(qualified, member);
      } else if (member.hasModifierProperty(PsiModifier.PROTECTED)) {
        final PsiExpression qualifier = qualified.getQualifierExpression();
        if (qualifier != null && !(qualifier instanceof PsiThisExpression) && !(qualifier instanceof PsiSuperExpression)) {
          visitPackageLocalMemberReference(qualified, member);
        } else {
          if (!isInInheritor(qualified, descendantOf)) {
            visitPackageLocalMemberReference(qualified, member);
          }
        }
      }
    }

    private boolean isInInheritor(PsiReferenceExpression qualified, final RefactoringUtil.IsDescendantOf descendantOf) {
      PsiClass aClass = PsiTreeUtil.getParentOfType(qualified, PsiClass.class);
      while (aClass != null) {
        if (descendantOf.value(aClass)) return true;
        aClass = PsiTreeUtil.getParentOfType(aClass, PsiClass.class);
      }
      return false;
    }

    private void visitPackageLocalMemberReference(PsiJavaCodeReferenceElement qualified, PsiModifierListOwner member) {
      PsiElement container = ConflictsUtil.getContainer(qualified);
      HashSet<PsiElement> reportedContainers = myReportedElementToContainer.get(member);
      if (reportedContainers == null) {
        reportedContainers = new HashSet<PsiElement>();
        myReportedElementToContainer.put(member, reportedContainers);
      }

      if (!reportedContainers.contains(container)) {
        reportedContainers.add(container);
        if (!isInsideMoved(container)) {
          PsiFile containingFile = container.getContainingFile();
          if (containingFile != null) {
            PsiDirectory directory = containingFile.getContainingDirectory();
            if (directory != null) {
              PsiPackage aPackage = JavaDirectoryService.getInstance().getPackage(directory);
              if (!myTargetPackage.equalToPackage(aPackage)) {
                String message = RefactoringBundle.message("0.will.be.inaccessible.from.1", RefactoringUIUtil.getDescription(member, true),
                                                      RefactoringUIUtil.getDescription(container, true));
                myConflicts.add(CommonRefactoringUtil.capitalize(message));
              }
            }
          }
        }
      }
    }

    public void visitTypeCast(PsiTypeCastExpression typeCastExpression,
                              PsiExpression instanceRef,
                              PsiElement referencedInstance) {
    }

    public void visitReadUsage(PsiExpression instanceRef, PsiType expectedType, PsiElement referencedInstance) {
    }

    public void visitWriteUsage(PsiExpression instanceRef, PsiType assignedType, PsiElement referencedInstance) {
    }
  }

  private static class NonPublicClassMemberWrappersSet extends HashSet<ClassMemberWrapper> {
    public void addElement(PsiMember member) {
      final PsiNamedElement namedElement = (PsiNamedElement)member;
      if (member.hasModifierProperty(PsiModifier.PUBLIC)) return;
      if (member.hasModifierProperty(PsiModifier.PRIVATE)) return;
      add(new ClassMemberWrapper(namedElement));
    }

    public void addElements(PsiMember[] members) {
      for (PsiMember member : members) {
        addElement(member);
      }
    }
  }
}
