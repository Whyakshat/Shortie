#!/usr/bin/env bash
mkdir -p out
javac -d out src/com/urlshortener/*.java
java -Dport=8080 -cp out com.urlshortener.Main
