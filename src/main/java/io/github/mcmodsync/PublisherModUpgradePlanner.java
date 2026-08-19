package io.github.mcmodsync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Plans GUI mod-row replacement without guessing through ambiguous mod IDs. */
final class PublisherModUpgradePlanner {
    private PublisherModUpgradePlanner() {
    }

    record ExistingMod(String path, String modId, String version) {
    }

    record CurrentMod(String path, String modId, String version) {
    }

    record Plan(
            Map<String, String> inheritedFromByCurrentPath,
            Map<String, String> conflictByCurrentPath,
            Set<String> newCurrentPaths,
            Set<String> staleExistingPaths) {
        Plan {
            inheritedFromByCurrentPath = Map.copyOf(inheritedFromByCurrentPath);
            conflictByCurrentPath = Map.copyOf(conflictByCurrentPath);
            newCurrentPaths = Set.copyOf(newCurrentPaths);
            staleExistingPaths = Set.copyOf(staleExistingPaths);
        }
    }

    static Plan plan(List<ExistingMod> existing, List<CurrentMod> current) {
        LinkedHashMap<String, ExistingMod> existingByPath = new LinkedHashMap<>();
        HashMap<String, List<ExistingMod>> existingById = new HashMap<>();
        for (ExistingMod item : existing) {
            existingByPath.putIfAbsent(normalizePath(item.path()), item);
            String id = normalizeId(item.modId());
            if (!id.isBlank()) existingById.computeIfAbsent(id, ignored -> new ArrayList<>()).add(item);
        }

        HashMap<String, List<CurrentMod>> currentById = new HashMap<>();
        for (CurrentMod item : current) {
            String id = normalizeId(item.modId());
            if (!id.isBlank()) currentById.computeIfAbsent(id, ignored -> new ArrayList<>()).add(item);
        }

        LinkedHashMap<String, String> conflicts = new LinkedHashMap<>();
        currentById.forEach((id, values) -> {
            if (values.size() < 2) return;
            String detail = "同一 modId=" + id + " 检测到多个 JAR：" + values.stream()
                    .map(value -> value.path() + versionSuffix(value.version()))
                    .sorted()
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
            for (CurrentMod value : values) conflicts.put(normalizePath(value.path()), detail);
        });

        LinkedHashMap<String, String> inherited = new LinkedHashMap<>();
        HashSet<String> usedExisting = new HashSet<>();
        HashSet<String> newCurrent = new HashSet<>();
        for (CurrentMod item : current) {
            String currentPath = normalizePath(item.path());
            if (conflicts.containsKey(currentPath)) continue;

            ExistingMod match = existingByPath.get(currentPath);
            if (match != null && usedExisting.contains(normalizePath(match.path()))) match = null;
            if (match == null) {
                String id = normalizeId(item.modId());
                List<CurrentMod> currentWithId = currentById.getOrDefault(id, List.of());
                List<ExistingMod> existingWithId = existingById.getOrDefault(id, List.of());
                if (!id.isBlank() && currentWithId.size() == 1 && existingWithId.size() == 1) {
                    ExistingMod candidate = existingWithId.getFirst();
                    if (!usedExisting.contains(normalizePath(candidate.path()))) match = candidate;
                }
            }

            if (match == null) {
                newCurrent.add(currentPath);
            } else {
                String oldPath = normalizePath(match.path());
                usedExisting.add(oldPath);
                inherited.put(currentPath, oldPath);
            }
        }

        HashSet<String> stale = new HashSet<>();
        for (ExistingMod item : existing) {
            String path = normalizePath(item.path());
            if (!usedExisting.contains(path)) stale.add(path);
        }
        return new Plan(inherited, conflicts, newCurrent, stale);
    }

    private static String versionSuffix(String version) {
        String value = version == null ? "" : version.strip();
        return value.isBlank() ? "" : " (" + value + ")";
    }

    static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeId(String modId) {
        return modId == null ? "" : modId.strip().toLowerCase(Locale.ROOT);
    }
}
