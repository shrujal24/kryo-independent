/* Copyright (c) 2008-2025, Nathan Sweet
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package com.esotericsoftware.kryo.serializers;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.IOException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;

import java.security.SecureRandom;
import javax.crypto.spec.GCMParameterSpec;


/** Encrypts data using the blowfish cipher.
 * @author Nathan Sweet */
public class BlowfishSerializer extends Serializer {
    private final Serializer serializer;
    private static SecretKeySpec keySpec;
    private static final String ALGO = "AES/GCM/NoPadding";  // <— NEW LINE
	private static final int GCM_TAG_LENGTH = 128;      // 128-bit tag
    private static final int GCM_IV_LENGTH = 12;	// 96-bit recommended IV for GCM


	public BlowfishSerializer (Serializer serializer, byte[] key) {
		this.serializer = serializer;
		keySpec = new SecretKeySpec(key, "AES");	// <— NEW LINE
	}

	public void write (Kryo kryo, Output output, Object object) {
		byte[] iv = new byte[GCM_IV_LENGTH];
		new SecureRandom().nextBytes(iv);

		Cipher cipher = getCipher(Cipher.ENCRYPT_MODE, iv);
		output.writeInt(iv.length);
		output.writeBytes(iv);
		
		CipherOutputStream cipherStream = new CipherOutputStream(output, cipher);
		Output cipherOutput = new Output(cipherStream, 256) {
			public void close () throws KryoException {
				// Don't allow the CipherOutputStream to close the output.
			}
		};
		serializer.write(kryo, cipherOutput, object);
		cipherOutput.flush();
		try {
			cipherStream.close();
		} catch (IOException ex) {
			throw new KryoException(ex);
		}
	}

	public Object read (Kryo kryo, Input input, Class type) {
		// Read the IV from the input stream first
		int ivLength = input.readInt();
		byte[] iv = input.readBytes(ivLength);
		
		Cipher cipher = getCipher(Cipher.DECRYPT_MODE, iv);
		CipherInputStream cipherInput = new CipherInputStream(input, cipher);
		return serializer.read(kryo, new Input(cipherInput, 256), type);
	}

	public Object copy (Kryo kryo, Object original) {
		return serializer.copy(kryo, original);
	}

	private static Cipher getCipher (int mode) {
		// Generate a random IV for encryption
		byte[] iv = new byte[GCM_IV_LENGTH];
		new SecureRandom().nextBytes(iv);
		return getCipher(mode, iv);
	}

	private static Cipher getCipher (int mode, byte[] iv) {
		try {
			Cipher cipher = Cipher.getInstance(ALGO);
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
			cipher.init(mode, keySpec, gcmSpec);
			return cipher;
		} catch (Exception ex) {
			throw new KryoException(ex);
		}
	}

}
