// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import ai.vespa.rankingexpression.importer.configmodelview.ImportedMlModels;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.search.query.profile.QueryProfileRegistry;
import com.yahoo.searchdefinition.RankProfileRegistry;
import com.yahoo.searchdefinition.RankingConstant;
import com.yahoo.searchdefinition.RankingConstants;
import com.yahoo.vespa.config.search.RankProfilesConfig;
import com.yahoo.searchdefinition.RankProfile;
import com.yahoo.searchdefinition.Search;
import com.yahoo.vespa.config.search.core.RankingConstantsConfig;
import com.yahoo.vespa.model.AbstractService;

import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The derived rank profiles of a search definition
 *
 * @author bratseth
 */
public class RankProfileList extends Derived implements RankProfilesConfig.Producer, RankingConstantsConfig.Producer {

    private static final Logger log = Logger.getLogger(RankProfileList.class.getName());

    private final Map<String, RawRankProfile> rankProfiles = new java.util.LinkedHashMap<>();
    private final RankingConstants rankingConstants;

    public static RankProfileList empty = new RankProfileList();

    private RankProfileList() {
        this.rankingConstants = new RankingConstants();
    }

    /**
     * Creates a rank profile
     *
     * @param search the search definition this is a rank profile from
     * @param attributeFields the attribute fields to create a ranking for
     */
    public RankProfileList(Search search,
                           RankingConstants rankingConstants,
                           AttributeFields attributeFields,
                           RankProfileRegistry rankProfileRegistry,
                           QueryProfileRegistry queryProfiles,
                           ImportedMlModels importedModels,
                           ModelContext.Properties deployProperties) {
        setName(search == null ? "default" : search.getName());
        this.rankingConstants = rankingConstants;
        deriveRankProfiles(rankProfileRegistry, queryProfiles, importedModels, search, attributeFields, deployProperties);
    }

    private void deriveRankProfiles(RankProfileRegistry rankProfileRegistry,
                                    QueryProfileRegistry queryProfiles,
                                    ImportedMlModels importedModels,
                                    Search search,
                                    AttributeFields attributeFields,
                                    ModelContext.Properties deployProperties) {
        if (search != null) { // profiles belonging to a search have a default profile
            RawRankProfile defaultProfile = new RawRankProfile(rankProfileRegistry.get(search, "default"),
                                                               queryProfiles, importedModels, attributeFields, deployProperties);
            rankProfiles.put(defaultProfile.getName(), defaultProfile);
        }

        for (RankProfile rank : rankProfileRegistry.rankProfilesOf(search)) {
            if (search != null && "default".equals(rank.getName())) continue;

            RawRankProfile rawRank = new RawRankProfile(rank, queryProfiles, importedModels, attributeFields, deployProperties);
            rankProfiles.put(rawRank.getName(), rawRank);
        }
    }

    public Map<String, RawRankProfile> getRankProfiles() {
        return rankProfiles;
    }

    /** Returns the raw rank profile with the given name, or null if it is not present */
    public RawRankProfile getRankProfile(String name) {
        return rankProfiles.get(name);
    }

    public void sendConstantsTo(Collection<? extends AbstractService> services) {
        rankingConstants.sendTo(services);
    }

    @Override
    public String getDerivedName() { return "rank-profiles"; }

    @Override
    public void getConfig(RankProfilesConfig.Builder builder) {
        for (RawRankProfile rank : rankProfiles.values() ) {
            rank.getConfig(builder);
        }
    }

    @Override
    public void getConfig(RankingConstantsConfig.Builder builder) {
        for (RankingConstant constant : rankingConstants.asMap().values()) {
            if ("".equals(constant.getFileReference()))
                log.warning("Illegal file reference " + constant); // Let tests pass ... we should find a better way
            else
                builder.constant(new RankingConstantsConfig.Constant.Builder()
                                         .name(constant.getName())
                                         .fileref(constant.getFileReference())
                                         .type(constant.getType()));
        }
    }

}
