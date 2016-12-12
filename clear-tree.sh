#!/bin/zsh

for i in {0..45}; do 
    curl --data '' "http://10.0.14.153:8080/led/$i/targetcolor?red=0&green=0&blue=0"; 
done;  

