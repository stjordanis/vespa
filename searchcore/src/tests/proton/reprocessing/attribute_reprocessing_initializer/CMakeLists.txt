# Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
find_package(GTest REQUIRED)
vespa_add_executable(searchcore_attribute_reprocessing_initializer_test_app TEST
    SOURCES
    attribute_reprocessing_initializer_test.cpp
    DEPENDS
    searchcore_reprocessing
    searchcore_attribute
    searchcore_pcommon
    GTest::GTest
)
vespa_add_test(NAME searchcore_attribute_reprocessing_initializer_test_app COMMAND searchcore_attribute_reprocessing_initializer_test_app)
