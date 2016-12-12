#!/bin/zsh

for j in {0..20}; do 
    for i in {0..45}; do 
        curl --data '' "http://10.0.14.153:8080/led/$i/targetcolor?red=0&green=0&blue=255"; 
    done;  
    sleep 3
    for i in {0..45}; do 
        curl --data '' "http://10.0.14.153:8080/led/$i/targetcolor?red=255&green=0&blue=0"; 
    done;  
    sleep 3
    for i in {0..45}; do 
        curl --data '' "http://10.0.14.153:8080/led/$i/targetcolor?red=0&green=255&blue=0"; 
    done;  
    sleep 3
    for i in {0..45}; do 
        curl --data '' "http://10.0.14.153:8080/led/$i/targetcolor?red=0&green=0&blue=0"; 
    done;  
    for i in {0..45}; do 
        curl --data '' "http://10.0.14.153:8080/led/$i/color?red=255&green=255&blue=255"; 
    done;  
    for i in {0..45}; do 
        curl --data '' "http://10.0.14.153:8080/led/$i/color?red=0&green=0&blue=255"; 
    done;  
    for i in {0..45}; do 
        curl --data '' "http://10.0.14.153:8080/led/$i/color?red=255&green=255&blue=0"; 
    done;  
    sleep 1
done ;

