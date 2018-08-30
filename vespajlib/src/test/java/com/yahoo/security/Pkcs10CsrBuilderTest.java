// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.security;

import org.junit.Test;

import javax.security.auth.x500.X500Principal;
import java.security.KeyPair;

import static org.junit.Assert.assertEquals;

/**
 * @author bjorncs
 */
public class Pkcs10CsrBuilderTest {

    @Test
    public void can_build_csr_with_sans() {
        X500Principal subject = new X500Principal("CN=subject");
        KeyPair keypair = KeyUtils.generateKeypair(KeyAlgorithm.RSA, 2048);
        Pkcs10Csr csr = Pkcs10CsrBuilder.fromKeypair(subject, keypair, SignatureAlgorithm.SHA256_WITH_RSA)
                .addSubjectAlternativeName("san1.com")
                .addSubjectAlternativeName("san2.com")
                .build();
        assertEquals(subject, csr.getSubject());
    }

}