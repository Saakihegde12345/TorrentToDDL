#!/bin/sh
set -eu
exec java -Djava.awt.headless=true -Xmx768m -cp '/opt/torrenttoddl/lib/*' com.example.torrenttoddl.desktop.MainKt --headless /data
