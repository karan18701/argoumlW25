/* $Id$
 *****************************************************************************
 * Copyright (c) 2009-2012 Contributors - see below
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    bobtarling
 *****************************************************************************
 *
 * Some portions of this file was previously release using the BSD License:
 */

// Copyright (c) 2007-2008 The Regents of the University of California. All
// Rights Reserved. Permission to use, copy, modify, and distribute this
// software and its documentation without fee, and without a written
// agreement is hereby granted, provided that the above copyright notice
// and this paragraph appear in all copies. This software program and
// documentation are copyrighted by The Regents of the University of
// California. The software program and documentation are supplied "AS
// IS", without any accompanying services from The Regents. The Regents
// does not warrant that the operation of the program will be
// uninterrupted or error-free. The end-user understands that the program
// was developed for research purposes and is advised not to rely
// exclusively on the program for any reason. IN NO EVENT SHALL THE
// UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
// SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
// ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
// THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF
// SUCH DAMAGE. THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE
// PROVIDED HEREUNDER IS ON AN "AS IS" BASIS, AND THE UNIVERSITY OF
// CALIFORNIA HAS NO OBLIGATIONS TO PROVIDE MAINTENANCE, SUPPORT,
// UPDATES, ENHANCEMENTS, OR MODIFICATIONS.

package org.argouml.profile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads model data from a Zip file.
 */
public class ZipModelLoader extends StreamModelLoader {

    private static final Logger LOG = Logger.getLogger(ZipModelLoader.class.getName());

    private static final int MAX_ENTRIES = 1000;
    private static final long MAX_TOTAL_UNCOMPRESSED_SIZE = 500L * 1024 * 1024; // 500 MB
    private static final long MAX_ENTRY_UNCOMPRESSED_SIZE = 100L * 1024 * 1024; // 100 MB
    private static final double MAX_COMPRESSION_RATIO = 100.0;

    public Collection loadModel(ProfileReference reference) throws ProfileException {
        LOG.log(Level.INFO, "Loading profile from ZIP {0}", reference.getPath());

        if (!reference.getPath().endsWith("zip")) {
            throw new ProfileException("Profile could not be loaded!");
        }

        InputStream is = null;
        File modelFile = new File(reference.getPath());
        String filename = modelFile.getName();
        String extension = filename.substring(filename.indexOf('.'), filename.lastIndexOf('.'));
        String path = modelFile.getParent();

        if (path != null) {
            System.setProperty("org.argouml.model.modules_search_path", path);
        }

        try {
            is = openZipStreamAt(modelFile.toURI().toURL(), extension);
        } catch (MalformedURLException e) {
            LOG.log(Level.SEVERE, "Exception while loading profile '" + reference.getPath() + "'", e);
            throw new ProfileException(e);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Exception while loading profile '" + reference.getPath() + "'", e);
            throw new ProfileException(e);
        }

        if (is == null) {
            throw new ProfileException("Profile could not be loaded!");
        }

        return super.loadModel(is, reference.getPublicReference());
    }

    /**
     * Securely opens a ZipInputStream and finds an entry with the given extension.
     * Prevents zip bomb vulnerabilities.
     */
    private ZipInputStream openZipStreamAt(URL url, String ext) throws IOException {
        ZipInputStream zis = new ZipInputStream(url.openStream());
        ZipEntry entry;
        int entryCount = 0;
        long totalUncompressedSize = 0;

        while ((entry = zis.getNextEntry()) != null) {
            entryCount++;
            if (entryCount > MAX_ENTRIES) {
                throw new IOException("Too many entries in ZIP file — possible zip bomb.");
            }

            // Path validation (Zip Slip)
            String entryName = entry.getName();
            if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
                throw new IOException("Invalid entry path — possible zip slip: " + entryName);
            }

            long uncompressedSize = entry.getSize();
            long compressedSize = entry.getCompressedSize();

            if (uncompressedSize > MAX_ENTRY_UNCOMPRESSED_SIZE) {
                throw new IOException("ZIP entry too large — possible zip bomb.");
            }

            if (uncompressedSize > 0 && compressedSize > 0) {
                double ratio = (double) uncompressedSize / compressedSize;
                if (ratio > MAX_COMPRESSION_RATIO) {
                    throw new IOException("Suspicious compression ratio — possible zip bomb.");
                }
            }

            totalUncompressedSize += uncompressedSize > 0 ? uncompressedSize : 0;
            if (totalUncompressedSize > MAX_TOTAL_UNCOMPRESSED_SIZE) {
                throw new IOException("Total uncompressed size too large — possible zip bomb.");
            }

            if (entryName.endsWith(ext)) {
                return zis; // return stream positioned at this entry
            }
        }

        return null; // if no valid entry found
    }
}
