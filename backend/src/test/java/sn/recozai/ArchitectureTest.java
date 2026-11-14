package sn.recozai;

import com.tngtech.archunit.junit.*;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages="sn.recozai",importOptions=com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest static final ArchRule domainIndependent=noClasses().that().resideInAnyPackage("..domain..","..application..","..api..","..shared..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..","jakarta.persistence..","..adapter..","..platform..","..config..","tools.jackson..");
    @ArchTest static final ArchRule identityBoundaries=noClasses().that().resideInAPackage("..identity..")
        .should().dependOnClassesThat().resideInAnyPackage("..clinic.adapter..","..audit.adapter..","..payer.adapter..","..claim.adapter..","..document.adapter..");
    @ArchTest static final ArchRule clinicBoundaries=noClasses().that().resideInAPackage("..clinic..")
        .should().dependOnClassesThat().resideInAnyPackage("..identity.adapter..","..audit.adapter..","..payer.adapter..","..claim.adapter..","..document.adapter..");
    @ArchTest static final ArchRule payerBoundaries=noClasses().that().resideInAPackage("..payer..")
        .should().dependOnClassesThat().resideInAnyPackage("..identity.adapter..","..clinic.adapter..","..audit.adapter..","..claim.adapter..","..document.adapter..");
    @ArchTest static final ArchRule claimBoundaries=noClasses().that().resideInAPackage("..claim..")
        .should().dependOnClassesThat().resideInAnyPackage("..identity.adapter..","..clinic.adapter..","..audit.adapter..","..payer.adapter..","..document.adapter..");
    @ArchTest static final ArchRule documentBoundaries=noClasses().that().resideInAPackage("..document..")
        .should().dependOnClassesThat().resideInAnyPackage("..identity.adapter..","..clinic.adapter..","..audit.adapter..","..payer.adapter..","..claim.adapter..");
}
