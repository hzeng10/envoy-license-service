# Coding conventions

## Java imports
Never use wildcard imports (`import com.example.*;`, `import java.util.*;`).
Always import each class individually (`import java.util.List;`, `import java.util.Map;`).
This applies to both `import` and `import static` statements.

## Unit tests
Tests must exercise real code branches, not return stub values that happen to satisfy the assertion.
Verify observable behaviour: what the code actually computes, not just that it returns something.
Every test should trace a specific path through the production code — if a new test is identical in behaviour to an existing one, it adds no value.
