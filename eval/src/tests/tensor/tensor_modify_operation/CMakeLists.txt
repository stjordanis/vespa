# Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
find_package(GTest REQUIRED)
vespa_add_executable(eval_tensor_modify_operation_test_app TEST
    SOURCES
    tensor_modify_operation_test.cpp
    DEPENDS
    vespaeval
    GTest::GTest
)
vespa_add_test(NAME eval_tensor_modify_operation_test_app COMMAND eval_tensor_modify_operation_test_app)
