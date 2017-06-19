/**
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *   disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 *   derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.storm.spout.sideline.config;

import com.google.common.collect.Maps;
import com.salesforce.storm.spout.sideline.config.annotation.Documentation;
import com.google.common.base.Preconditions;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.TreeMap;

/**
 * 
 * When executed, this class will update the Configuration section of README.md file.
 * The old file will be saved as README.md.bak.
 *
 */
public class ConfigPrinter {

    private static String DELIMITER = " | ";
    private static final String CONFIGURATION_BEGIN_DELIMITER = "[//]: <> (CONFIGURATION_BEGIN_DELIMITER)";
    private static final String CONFIGURATION_END_DELIMITER = "[//]: <> (CONFIGURATION_END_DELIMITER)";

    public static void main(String[] args) throws Exception {
        // Assume folders and files are relative to the project root
        Path readmePath = Paths.get("README.md");
        Path readmeTempOutPath = Paths.get("target/README.md");

        // Optionally backup the existing README file (will not override the previous backup) or
        // delete this section if backup is not desired (that's what we have github for, right?)
        Path readmeBackupPath = Paths.get("README.md.bak");
        final File readmeBackupFile = readmeBackupPath.toFile();
        if (readmeBackupFile.exists()) {
            System.out.println("The backup file exists and cannot be overwtitten.");
            System.out.println("Manually delete it first: " + readmeBackupFile.getAbsolutePath());
            return;
        }

        Files.copy(readmePath, readmeBackupPath);

        final File readmeFile = readmePath.toFile();
        Preconditions.checkArgument(readmeFile.exists() && readmeFile.isFile(), "README.md file must exist: %s", readmeFile.getAbsolutePath());

        try (BufferedReader readmeReader = Files.newBufferedReader(readmePath, StandardCharsets.UTF_8);
            PrintWriter readmePrintWriter = new PrintWriter(Files.newBufferedWriter(readmeTempOutPath, StandardCharsets.UTF_8))) {
            String line;
            boolean insideConfigurationSection = false;
            boolean configurationSectionFound = false;

            while ((line = readmeReader.readLine()) != null) {
                if (CONFIGURATION_BEGIN_DELIMITER.equals(line)) {
                    insideConfigurationSection = true;
                    configurationSectionFound = true;
                    readmePrintWriter.println(line);
                } else if (CONFIGURATION_END_DELIMITER.equals(line)) {
                    mergeConfigSection(readmePrintWriter);
                    insideConfigurationSection = false;
                    readmePrintWriter.println(line);
                } else if (!insideConfigurationSection) {
                    readmePrintWriter.println(line);
                }
            }

            Preconditions.checkState(configurationSectionFound, "README.md did not have configuration section delimiters: %s", readmeFile.getAbsolutePath());
            Preconditions.checkState(!insideConfigurationSection, "README.md did not have closing configuration section delimiter: %s", readmeFile.getAbsolutePath());
        }
        Files.copy(readmeTempOutPath, readmePath, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Updated README file: " + readmeFile.getAbsolutePath());
    }

    /**
     * Injects configuration table into README.
     * 
     * @throws IllegalAccessException
     * @throws NoSuchFieldException
     */
    private static void mergeConfigSection(PrintWriter readmePrintWriter) throws IllegalAccessException, NoSuchFieldException {
        readmePrintWriter.println();

        Map<String, Object> config = SpoutConfig.setDefaults(Maps.newHashMap());

        Map<Documentation.Category, List<String>> lines = new TreeMap<>();

        Field[] fields = SpoutConfig.class.getDeclaredFields();

        for (Field field : fields) {
            // Presumably our configuration field...
            if (field.getType() == String.class) {
                final String configParam = (String) SpoutConfig.class.getField(field.getName()).get(SpoutConfig.class);

                Documentation documentation = field.getAnnotation(Documentation.class);

                StringBuilder builder = new StringBuilder();

                if (lines.get(documentation.category()) == null) {
                    lines.put(documentation.category(), new ArrayList<>());
                }

                String description = documentation.description();
                String type = documentation.type().getSimpleName();
                boolean required = documentation.required();
                String defaultValue = String.valueOf(config.getOrDefault(configParam, ""));

                builder.append(configParam).append(DELIMITER);
                builder.append(type).append(DELIMITER);
                builder.append(required ? "Required" : "").append(DELIMITER);
                builder.append(description).append(DELIMITER);
                builder.append(defaultValue);

                lines.get(documentation.category()).add(builder.toString());
            }
        }

        for (Documentation.Category category : lines.keySet()) {
            if (category != Documentation.Category.NONE) {
                readmePrintWriter.println("### " + category.toString());
            }

            readmePrintWriter.println("Config Key | Type | Required | Description | Default Value |");
            readmePrintWriter.println("---------- | ---- | -------- | ----------- | ------------- |");

            Collections.sort(lines.get(category));

            for (String line : lines.get(category)) {
                readmePrintWriter.println(line);
            }

            readmePrintWriter.println();
        }

        readmePrintWriter.println();
    }
}