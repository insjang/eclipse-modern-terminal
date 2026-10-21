#!/usr/bin/env bash
# Builds the Modern Terminal bundles into build/*.jar against an Eclipse installation, runs the unit tests,
# and with `install` copies the jars into $ECLIPSE/dropins (close Eclipse first).
# usage: ECLIPSE=/path/to/eclipse ./build.sh [install|--skip-tests]
set -euo pipefail
ECLIPSE=${ECLIPSE:?set ECLIPSE to an Eclipse 2026-09 or later installation}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/build
QUALIFIER=v$(date +%Y%m%d-%H%M%S)
# Eclipse's plugins, minus its own terminal: nothing may compile against it by accident
CP=$(find "$ECLIPSE/plugins" -maxdepth 1 -name '*.jar' ! -name 'org.eclipse.terminal.*' | tr '\n' ':')
BUNDLES="control view.core view.ui connector.process connector.local" # dependency order

rm -rf "$OUT" && mkdir -p "$OUT"
for b in $BUNDLES; do
	ID=io.github.insjang.terminal.$b; SRC=$HERE/bundles/$ID; C=$OUT/$ID
	mkdir -p "$C"
	javac -encoding UTF-8 --release 21 -nowarn -Xmaxerrs 30 -cp "$CP" -d "$C" $(find "$SRC/src" -name '*.java')
	CP="$C:$CP"
	rsync -a --include='*/' --exclude='*.java' --prune-empty-dirs "$SRC/src/" "$C/"
	# the bin.includes of build.properties, without the manifest (rewritten below)
	for f in plugin.xml plugin.properties .options README.txt about.html HelpContexts.xml contexts.xml icons OSGI-INF META-INF/p2.inf; do
		[ -e "$SRC/$f" ] && mkdir -p "$C/$(dirname "$f")" && cp -r "$SRC/$f" "$C/$f"
	done
	VER=$(sed -n 's/^Bundle-Version: //p' "$SRC/META-INF/MANIFEST.MF" | tr -d '\r' | sed "s/qualifier/$QUALIFIER/")
	sed -E "s/^Bundle-Version: .*/Bundle-Version: $VER/" "$SRC/META-INF/MANIFEST.MF" | tr -d '\r' > "$OUT/$ID.mf"
	(cd "$C" && jar cfm "$OUT/${ID}_$VER.jar" "$OUT/$ID.mf" .)
	echo "built ${ID}_$VER.jar"
done

if [ "${1:-}" != "--skip-tests" ]; then
	T=$HERE/tests/io.github.insjang.terminal.test/src/io/github/insjang/terminal
	P=io.github.insjang.terminal.internal
	mkdir -p "$OUT/tests"
	javac -encoding UTF-8 --release 21 -nowarn -cp "$CP" -d "$OUT/tests" $(find $T/internal/emulator $T/internal/model $T/model -name '*.java') "$HERE/tools/RunTests.java"
	java -cp "$OUT/tests:$CP" RunTests $P.emulator.VT100EmulatorBackendTest $P.emulator.VT100EmulatorTest $P.emulator.MouseReportTest \
		$P.model.CharWidthTest $P.model.TerminalTextDataStoreTest $P.model.TerminalTextDataTest $P.model.TerminalTextDataWindowTest \
		$P.model.TerminalTextDataFastScrollTest $P.model.SynchronizedTerminalTextDataTest $P.model.TerminalTextDataSnapshotTest
fi

if [ "${1:-}" = install ]; then
	rm -f "$ECLIPSE"/dropins/io.github.insjang.terminal.*.jar
	cp "$OUT"/io.github.insjang.terminal.*.jar "$ECLIPSE/dropins/"
	echo "installed into $ECLIPSE/dropins: restart Eclipse"
fi
