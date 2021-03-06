package net.thucydides.core.requirements;

import net.serenitybdd.core.collect.NewList;
import net.thucydides.core.model.Release;
import net.thucydides.core.model.ReportType;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.releases.ReleaseManager;
import net.thucydides.core.reports.html.ReportNameProvider;
import net.thucydides.core.requirements.model.Requirement;
import net.thucydides.core.util.EnvironmentVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;
import static net.thucydides.core.reports.html.ReportNameProvider.NO_CONTEXT;


public abstract class BaseRequirementsService implements RequirementsService {
    protected List<Requirement> requirements;
    protected List<Release> releases;
    private Map<Requirement, List<Requirement>> requirementAncestors;

    protected final EnvironmentVariables environmentVariables;

    private static final List<Requirement> NO_REQUIREMENTS = Collections.synchronizedList(new ArrayList<Requirement>());
    private static final Logger LOGGER = LoggerFactory.getLogger(BaseRequirementsService.class);

    public BaseRequirementsService(EnvironmentVariables environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public abstract List<Requirement> getRequirements();

    public abstract  List<? extends RequirementsTagProvider> getRequirementsTagProviders();

    public abstract Optional<ReleaseProvider> getReleaseProvider();

    protected List<Requirement> addParentsTo(List<Requirement> requirements) {
        return addParentsTo(requirements, null);
    }

    protected List<Requirement> addParentsTo(List<Requirement> requirements, String parent) {
        List<Requirement> augmentedRequirements = new ArrayList<>();
        for(Requirement requirement : requirements) {
            List<Requirement> children = requirement.hasChildren()
                    ? addParentsTo(requirement.getChildren(),requirement.getName()) : NO_REQUIREMENTS;
            augmentedRequirements.add(requirement.withParent(parent).withChildren(children));
        }
        return augmentedRequirements;
    }

    public java.util.Optional<Requirement> getParentRequirementFor(TestOutcome testOutcome) {

        try {
            for (RequirementsTagProvider tagProvider : getRequirementsTagProviders()) {
                java.util.Optional<Requirement> requirement = getParentRequirementOf(testOutcome, tagProvider);
                if (requirement.isPresent()) {
                    return requirement;
                }
            }
        } catch (RuntimeException handleTagProvidersElegantly) {
            LOGGER.error("Tag provider failure", handleTagProvidersElegantly);
        }
        return java.util.Optional.empty();
    }

    public java.util.Optional<Requirement> getRequirementFor(TestTag tag) {

        try {
            for (RequirementsTagProvider tagProvider : getRequirementsTagProviders()) {
                java.util.Optional<Requirement> requirement = tagProvider.getRequirementFor(tag);
                if (requirement.isPresent()) {
                    return requirement;
                }
            }
        } catch (RuntimeException handleTagProvidersElegantly) {
            LOGGER.error("Tag provider failure", handleTagProvidersElegantly);
        }
        return Optional.empty();
    }

    public List<Requirement> getAncestorRequirementsFor(TestOutcome testOutcome) {
        for (RequirementsTagProvider tagProvider : getRequirementsTagProviders()) {


            java.util.Optional<Requirement> requirement = getParentRequirementOf(testOutcome, tagProvider);
            if (requirement.isPresent()) {
                LOGGER.debug("Requirement found for test outcome " + testOutcome.getTitle() + "-" + testOutcome.getIssueKeys() + ": " + requirement);
                Optional<Requirement> matchingAncestor = matchingAncestorFor(requirement.get());
                if (matchingAncestor.isPresent()) {
//                    Requirement matchingAncestor = matchingAncestorFor(requirement.get()).get();
                    return getRequirementAncestors().get(matchingAncestor.get());
//                }
//
//                if ((getRequirementAncestors() != null) && (getRequirementAncestors().containsKey(requirement.get()))) {
//                    return getRequirementAncestors().get(requirement.get());
                } else {
                    LOGGER.warn("Requirement without identified ancestors found test outcome " + testOutcome.getTitle() + "-" + testOutcome.getIssueKeys() + ": " + requirement);
                }
            }
        }
        return EMPTY_LIST;
    }

    Optional<Requirement> matchingAncestorFor(Requirement requirement) {
        return getRequirementAncestors().keySet().stream().filter(
               requirementKey -> requirementKey.matches(requirement)
        ).findFirst();
    }


    protected void indexRequirements() {
        requirementAncestors = new ConcurrentHashMap();
        for (Requirement requirement : requirements) {
            List<Requirement> requirementPath = NewList.of(requirement);
            requirementAncestors.put(requirement, NewList.of(requirement));
            indexChildRequirements(requirementPath, requirement.getChildren());
        }
    }


    private void indexChildRequirements(List<Requirement> ancestors, List<Requirement> children) {
        for (Requirement requirement : children) {
            List<Requirement> requirementPath = new ArrayList(ancestors);
            requirementPath.add(requirement);
            requirementAncestors.put(requirement, NewList.copyOf(requirementPath));
            indexChildRequirements(requirementPath, requirement.getChildren());
        }
    }

    private ReleaseManager releaseManager;

    private ReleaseManager getReleaseManager() {
        if (releaseManager == null) {
            ReportNameProvider defaultNameProvider = new ReportNameProvider(NO_CONTEXT, ReportType.HTML, this);
            releaseManager = new ReleaseManager(environmentVariables, defaultNameProvider, this);
        }
        return releaseManager;
    }


    private Map<Requirement, List<Requirement>> getRequirementAncestors() {
        if (requirementAncestors == null) {
            getRequirements();
        }
        return requirementAncestors;
    }

    private java.util.Optional<Requirement> getParentRequirementOf(TestOutcome testOutcome, RequirementsTagProvider tagProvider) {

        java.util.Optional<Requirement> parentDefinedInTags = ParentRequirementsProvided.by(tagProvider).forOutcome(testOutcome);
        if (parentDefinedInTags.isPresent()) {
            java.util.Optional<Requirement> matchingIndexedParentRequirement = findMatchingIndexedRequirement(parentDefinedInTags.get());
            return matchingIndexedParentRequirement;
        }

        return java.util.Optional.empty();
    }

    private java.util.Optional<Requirement> findMatchingIndexedRequirement(Requirement requirement) {
        for(Requirement indexedRequirement : AllRequirements.in(requirements)) {
            if (requirement.matches(indexedRequirement)) {
                return java.util.Optional.of(mostPreciseOf(requirement,indexedRequirement));
            }
        }
        return java.util.Optional.empty();
    }

    private Requirement mostPreciseOf(Requirement thisRequirement, Requirement thatRequirement) {
        String thisParent = thisRequirement.getParent() != null ? thisRequirement.getParent() : "";
        String thatParent = thatRequirement.getParent() != null ? thatRequirement.getParent() : "";

        return (thatParent.length() >= thisParent.length()) ? thatRequirement : thisRequirement;
    }

    public List<Release> getReleasesFromRequirements() {
        if (releases == null) {
            if (getReleaseProvider().isPresent() && (getReleaseProvider().get().isActive())) {
                releases = getReleaseProvider().get().getReleases();
            } else {
                List<List<String>> releaseVersions = getReleaseVersionsFrom(getRequirements());
                releases = getReleaseManager().extractReleasesFrom(releaseVersions);
            }
        }
        return releases;
    }

    public List<String> getTopLevelRequirementTypes() {
        List<String> requirementTypes = new ArrayList<>();
        for(Requirement requirement : getRequirements()) {
            requirementTypes.add(requirement.getType());
        }
        return requirementTypes;
    }

    public List<String> getRequirementTypes() {
        List<String> requirementTypes = new ArrayList<>();
        for(String type : requirementTypesDefinedIn(getRequirements())) {
            if (!requirementTypes.contains(type)) {
                requirementTypes.add(type);
            }
        }
        return requirementTypes;
    }

    private Collection<String> requirementTypesDefinedIn(List<Requirement> requirements) {
        List<String> requirementTypes = new ArrayList<>();
        for(Requirement requirement : requirements) {
            if (!requirementTypes.contains(requirement.getType())) {
                requirementTypes.add(requirement.getType());
            }
            if (!requirement.getChildren().isEmpty()) {
                requirementTypes.addAll(requirementTypesDefinedIn(requirement.getChildren()));
            }
        }
        return requirementTypes;
    }

    private Collection<TestTag> requirementTagsOfType(List<Requirement> requirements, List<String> tagTypes) {
        Set<TestTag> requirementTypes = new HashSet<>();
        for(Requirement requirement : AllRequirements.in(requirements)) {
            List<TestTag> matchingTags = requirement.getTags().stream()
                                                    .filter( tag -> tagTypes.contains(tag.getType()))
                                                    .collect(Collectors.toList());

            requirementTypes.addAll(matchingTags);
        }
        return requirementTypes;
    }

    @Override
    public List<String> getReleaseVersionsFor(TestOutcome testOutcome) {
        List<String> releases = new ArrayList(testOutcome.getVersions());
        for (Requirement parentRequirement : getAncestorRequirementsFor(testOutcome)) {
            releases.addAll(parentRequirement.getReleaseVersions());
        }
        return releases;
    }


    private List<List<String>> getReleaseVersionsFrom(List<Requirement> requirements) {
        List<List<String>> releaseVersions = new ArrayList();
        for (Requirement requirement : requirements) {
            releaseVersions.add(requirement.getReleaseVersions());
            releaseVersions.addAll(getReleaseVersionsFrom(requirement.getChildren()));
        }
        return releaseVersions;
    }

    @Override
    public boolean isRequirementsTag(TestTag tag) {
        return getRequirementTypes().contains(tag.getType());
    }

    @Override
    public Collection<TestTag> getTagsOfType(List<String> tagTypes) {
        return requirementTagsOfType(getRequirements(), tagTypes);
    }
}
