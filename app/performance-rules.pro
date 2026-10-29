# Instrumentation is compiled separately and calls app/library APIs that production does not.
# Keep those entry points and names; still run R8's code optimization in a non-debuggable build.
-dontshrink
-dontobfuscate
