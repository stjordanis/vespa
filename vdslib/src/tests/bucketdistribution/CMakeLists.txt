# Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
find_package(GTest REQUIRED)
vespa_add_library(vdslib_bucketdistributiontest
    SOURCES
    bucketdistributiontest.cpp
    DEPENDS
    vdslib
    GTest::GTest
)
