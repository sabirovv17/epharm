"""Fail-closed reader for the Spring mapping syntax used by this repository."""

import json
import re


MAPPINGS = {"GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
            "PatchMapping": "PATCH", "DeleteMapping": "DELETE"}
CLIENT_ONLY = {f"POST /api/mobile/auth/password-reset/{action}"
               for action in ("request", "verify", "complete")}


def mask_kotlin(source, strings=False):
    """Keep offsets/newlines while hiding comments and optionally string/char literals."""
    masked = list(source)
    index = 0
    while index < len(source):
        start = index
        if source.startswith("//", index):
            index = source.find("\n", index)
            index = len(source) if index < 0 else index
        elif source.startswith("/*", index):
            depth, index = 1, index + 2
            while index < len(source) and depth:
                if source.startswith("/*", index):
                    depth, index = depth + 1, index + 2
                elif source.startswith("*/", index):
                    depth, index = depth - 1, index + 2
                else:
                    index += 1
            if depth:
                raise ValueError("unsupported unterminated Kotlin comment")
        elif source[index] in ('"', "'"):
            quote = '"""' if source.startswith('"""', index) else source[index]
            index += len(quote)
            while index < len(source) and not source.startswith(quote, index):
                index += 2 if source[index] == "\\" and len(quote) == 1 else 1
            if index >= len(source):
                raise ValueError("unsupported unterminated Kotlin literal")
            index += len(quote)
            if not strings:
                continue
        else:
            index += 1
            continue
        for position in range(start, index):
            if masked[position] != "\n":
                masked[position] = " "
    return "".join(masked)


def mapping_path(arguments):
    """Literal positional path, optional consumes/produces arrays; unknown syntax is an error."""
    if not arguments.strip():
        return ""
    visible = mask_kotlin(arguments, strings=True)
    parts, start, depth = [], 0, 0
    for index, character in enumerate(visible):
        if character in "[({":
            depth += 1
        elif character in "])}":
            depth -= 1
        elif character == "," and depth == 0:
            parts.append(arguments[start:index].strip())
            start = index + 1
    parts.append(arguments[start:].strip())
    path = ""
    for index, part in enumerate(filter(None, parts)):
        if part.startswith('"') and index == 0:
            path = json.loads(part)
            if not isinstance(path, str) or "$" in path or not path.startswith("/"):
                raise ValueError("unsupported non-literal/relative mapping path")
        elif not re.fullmatch(
                r'(consumes|produces)\s*=\s*\[\s*'
                r'(?:(?:MediaType\.[A-Z_]+|"[^"\\]*")\s*,?\s*)+\]', part):
            raise ValueError(f"unsupported mapping arguments: {part!r}")
    return path


def controller_endpoints(root):
    directory = root / "admin-panel/backend/src/main/kotlin"
    if not directory.is_dir():
        raise ValueError(f"backend source directory does not exist: {directory}")
    endpoints = {}
    for file in sorted(directory.rglob("*.kt")):
        source = file.read_text(encoding="utf-8")
        code, visible = mask_kotlin(source), mask_kotlin(source, strings=True)
        relative = file.relative_to(root)
        if re.search(r"(?m)^\s*import\s+org\.springframework\.web\.bind\.annotation\.[^\n]+\bas\b", visible):
            raise ValueError(f"{relative}: unsupported aliased Spring annotation import")
        annotations = list(re.finditer(r"@(?:[\w]+\.)*([A-Za-z]+(?:Mapping|Exchange))\b", visible))
        controllers = list(re.finditer(r"@(?:[\w]+\.)*(?:RestController|Controller)\b", visible))
        if not controllers and not annotations:
            continue
        if len(controllers) != 1:
            raise ValueError(f"{relative}: unsupported mapping/controller ownership")
        declaration = re.search(r"\bclass\s+\w+", visible[controllers[0].end():])
        if declaration is None:
            raise ValueError(f"{relative}: unsupported controller declaration")
        class_start = controllers[0].end() + declaration.start()
        body_start = visible.find("{", class_start)
        if body_start < 0:
            raise ValueError(f"{relative}: unsupported controller without body")
        prefix, prefix_seen = "", False
        for annotation in annotations:
            name = annotation.group(1)
            line = source.count("\n", 0, annotation.start()) + 1
            label = f"{relative}:{line}"
            end = annotation.end()
            while end < len(visible) and visible[end].isspace():
                end += 1
            arguments = ""
            if end < len(visible) and visible[end] == "(":
                start, depth = end + 1, 1
                end += 1
                while end < len(visible) and depth:
                    depth += (visible[end] == "(") - (visible[end] == ")")
                    end += 1
                if depth:
                    raise ValueError(f"{label}: unsupported unclosed mapping annotation")
                arguments = code[start:end - 1]
            try:
                path = mapping_path(arguments)
            except ValueError as error:
                raise ValueError(f"{label}: {error}") from error
            if name == "RequestMapping" and annotation.start() < class_start and not prefix_seen:
                prefix, prefix_seen = path, True
                continue
            body = visible[body_start:annotation.start()]
            if (name not in MAPPINGS or annotation.start() <= body_start or
                    body.count("{") - body.count("}") != 1):
                raise ValueError(f"{label}: unsupported mapping {name} or class/function ownership")
            # A mapping must annotate the next function, not a property/nested class or another mapping.
            following = visible[end:]
            target = re.search(r"\b(fun|class|val|var)\b|@(?:[\w]+\.)*\w+Mapping\b|[{}]", following)
            if target is None or target.group() != "fun":
                raise ValueError(f"{label}: unsupported mapping without an unambiguous function")
            route = prefix.rstrip("/") + path or "/"
            api = f"{MAPPINGS[name]} {route}"
            if api in endpoints:
                raise ValueError(f"{label}: ambiguous duplicate endpoint {api}; first at {endpoints[api]}")
            endpoints[api] = label
    if not endpoints:
        raise ValueError("no backend controller endpoints found; coverage cannot be established")
    return endpoints


def validate_api_coverage(items, root, errors):
    try:
        actual = controller_endpoints(root)
    except (OSError, ValueError) as error:
        errors.append(f"API coverage: {error}")
        return 0
    inventoried = set()
    for item in items:
        if not isinstance(item, dict) or not re.fullmatch(r"[HL][0-9]{2}", str(item.get("group"))):
            continue
        source = item.get("source")
        if not isinstance(source, dict) or not isinstance(source.get("api", []), list):
            continue
        for api in source.get("api", []):
            if not isinstance(api, str) or not re.fullmatch(r"(?:GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS) /\S*", api):
                errors.append(f"inventory[{item.get('id')}].source.api: unsupported endpoint reference {api!r}")
                continue
            inventoried.add(api)
            if api in CLIENT_ONLY and api not in actual and item.get("state") == "source_confirmed":
                errors.append(f"inventory[{item.get('id')}]: client-only endpoint cannot be source_confirmed: {api}")
    for api in sorted(actual.keys() - inventoried):
        errors.append(f"API coverage: missing inventory action for {api} ({actual[api]})")
    for api in sorted(inventoried - actual.keys() - CLIENT_ONLY):
        errors.append(f"API coverage: inventoried endpoint has no backend mapping: {api}")
    return len(actual)
