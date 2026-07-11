package com.github.catvod.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** 对齐 TV catvod Shell */
public class Shell {

    public static String exec(String command) {
        try {
            StringBuilder sb = new StringBuilder();
            Process p = Runtime.getRuntime().exec(command);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return Util.substring(sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
