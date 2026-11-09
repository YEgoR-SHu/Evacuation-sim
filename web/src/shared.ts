export const $ = <T extends Element>(selector: string): T =>
    document.querySelector(selector) as T;

const SVG_NS = 'http://www.w3.org/2000/svg';
const allowedTags = new Set(['svg', 'g', 'defs', 'symbol', 'use', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'path', 'text', 'tspan', 'title', 'desc', 'clipPath', 'mask', 'linearGradient', 'radialGradient', 'stop', 'pattern', 'style']);
const presentation = /^(fill|fill-opacity|fill-rule|stroke(?:-width|-opacity|-linecap|-linejoin|-miterlimit|-dasharray|-dashoffset)?|opacity|font(?:-family|-size|-weight|-style)?|text-anchor|dominant-baseline|display|visibility|vector-effect|paint-order|clip-path|mask)$/i;

function safeValue(value: string): boolean {
    if (/[\\]|(?:javascript|vbscript|data)\s*:|expression\s*\(|@import|\/\*/i.test(value)) return false;
    return !/url\s*\(/i.test(value.replace(/url\(\s*["']?#[\w:.-]+["']?\s*\)/gi, ''));
}

function rejectCssTransform(value: string) {
    if (/(?:^|[;{])\s*transform(?:-origin|-box)?\s*:/i.test(value.replace(/\/\*[\s\S]*?\*\//g, ''))) {
        throw new Error('CSS transform не поддерживается: используйте SVG transform.');
    }
}

function applyStyle(element: Element, value: string) {
    rejectCssTransform(value);
    (element as SVGElement).style.cssText = safeDeclarations(value).cssText;
}

function safeDeclarations(value: string): CSSStyleDeclaration {
    const parsed = document.createElementNS(SVG_NS, 'g').style;
    const safe = document.createElementNS(SVG_NS, 'g').style;
    parsed.cssText = value;
    for (const property of parsed) {
        const content = parsed.getPropertyValue(property);
        if (presentation.test(property) && safeValue(content)) {
            if (/\bvar\s*\(/i.test(content)) throw new Error('CSS-переменные в SVG пока не поддерживаются: задайте значения явно.');
            safe.setProperty(property, content, parsed.getPropertyPriority(property));
        }
    }
    return safe;
}

/** Resolve the browser's cascade in a separate document, then remove global styles. */
function inlineStyles(root: Element, box: readonly number[]) {
    const rules: { selector: string, declarations: CSSStyleDeclaration }[] = [];
    const styles = [...root.querySelectorAll('style')];
    if (!styles.length) return;
    for (const style of styles) {
        const css = style.textContent || '';
        if (/@/.test(css.replace(/\/\*[\s\S]*?\*\//g, ''))) {
            throw new Error('SVG поддерживает обычные CSS-правила; правила с @ нужно раскрыть перед загрузкой.');
        }
        const sheet = new CSSStyleSheet();
        sheet.replaceSync(css);
        const safeRules: string[] = [];
        for (const rule of sheet.cssRules) {
            if (!(rule instanceof CSSStyleRule) || ('cssRules' in rule && rule.cssRules.length)) {
                throw new Error('Вложенные CSS-правила SVG пока не поддерживаются.');
            }
            const declarations = safeDeclarations(rule.style.cssText);
            rules.push({ selector: rule.selectorText, declarations });
            safeRules.push(`${rule.selectorText} {${declarations.cssText}}`);
        }
        style.textContent = safeRules.join('\n');
    }
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-same-origin');
    frame.setAttribute('aria-hidden', 'true');
    frame.style.cssText = `position:fixed;left:-100000px;top:0;width:${Math.min(2048, box[2])}px;height:${Math.min(2048, box[3])}px;visibility:hidden;pointer-events:none;border:0`;
    try {
        (document.body || document.documentElement).append(frame);
        const isolatedDocument = frame.contentDocument, isolatedWindow = frame.contentWindow;
        if (!isolatedDocument || !isolatedWindow) throw new Error('Не удалось подготовить изолированный SVG.');
        const isolatedRoot = isolatedDocument.importNode(root, true);
        isolatedDocument.replaceChild(isolatedRoot, isolatedDocument.documentElement);
        const originalElements = [root, ...root.querySelectorAll('*')];
        const isolatedElements = [isolatedRoot, ...isolatedRoot.querySelectorAll('*')];
        const reusable = new Set<Element>();
        for (const use of isolatedRoot.querySelectorAll('use')) {
            const id = use.getAttribute('href') || use.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
            const target = id?.startsWith('#') ? isolatedDocument.getElementById(id.slice(1)) : null;
            if (target) { reusable.add(target); for (const child of target.querySelectorAll('*')) reusable.add(child); }
        }
        const frozen: { element: SVGElement, property: string, value: string }[] = [];
        for (const [index, element] of isolatedElements.entries()) {
            if (element.localName === 'style') continue;
            const properties = new Set<string>();
            for (const rule of rules) {
                if (!element.matches(rule.selector)) continue;
                for (const property of rule.declarations) {
                    const value = rule.declarations.getPropertyValue(property);
                    if (reusable.has(element) && /\b(?:inherit|unset|revert(?:-layer)?|currentcolor|context-fill|context-stroke)\b|(?:\d|\.)\s*(?:(?:em|ex|ch|rem)\b|%)/i.test(value)) {
                        throw new Error('В CSS повторно используемой SVG-геометрии задайте явные значения вместо наследуемых или относительных.');
                    }
                    properties.add(property);
                }
            }
            const computed = isolatedWindow.getComputedStyle(element);
            for (const property of properties) {
                const value = computed.getPropertyValue(property).replace(/url\(\s*(["']?)(.*?)\1\s*\)/gi, (_match, _quote: string, reference: string) => {
                    const fragment = new URL(reference, isolatedDocument.baseURI).hash;
                    if (!/^#[\w:.-]+$/.test(fragment)) throw new Error('SVG содержит неподдерживаемую ссылку CSS.');
                    return `url("${fragment}")`;
                });
                frozen.push({ element: originalElements[index] as SVGElement, property, value });
            }
        }
        // Do not copy inherited defaults into defs: a use instance must still
        // inherit its own fill/font rather than those of the source definition.
        for (const { element, property, value } of frozen) element.style.setProperty(property, value, 'important');
        for (const style of styles) style.remove();
    } finally { frame.remove(); }
}

/** Return a detached, inert SVG. Styles are scoped by applying SVG presentation rules to their own elements. */
export function safeSvgElement(text: string, vb?: readonly number[]): SVGSVGElement {
    if (new TextEncoder().encode(text).length > 2 * 1024 * 1024) throw new Error('SVG превышает 2 МиБ.');
    if (/<!DOCTYPE|<!ENTITY|<\?xml-stylesheet/i.test(text)) throw new Error('Внешние сущности и таблицы стилей SVG не поддерживаются.');
    const doc = new DOMParser().parseFromString(text, 'image/svg+xml');
    const root = doc.documentElement;
    if (doc.querySelector('parsererror') || root.localName !== 'svg' || root.namespaceURI !== SVG_NS) {
        throw new Error('Файл не похож на корректный SVG.');
    }
    for (const style of root.querySelectorAll('style')) rejectCssTransform(style.textContent || '');
    for (const element of [root, ...root.querySelectorAll('*')]) rejectCssTransform(element.getAttribute('style') || '');
    for (const element of [...root.querySelectorAll('*')]) {
        if (element.namespaceURI !== SVG_NS || !allowedTags.has(element.localName)) element.remove();
    }
    for (const element of [root, ...root.querySelectorAll('*')]) {
        for (const attribute of [...element.attributes]) {
            const name = attribute.localName.toLowerCase();
            const value = attribute.value;
            if (name === 'style') {
                element.removeAttributeNode(attribute);
                applyStyle(element, value);
            } else if (name.startsWith('on') || attribute.name.toLowerCase() === 'xml:base' || name === 'tabindex' || name === 'autofocus' || name === 'contenteditable' ||
                (name === 'href' && !/^#[\w:.-]+$/.test(value.trim())) ||
                (!name.startsWith('data-') && !safeValue(value))) {
                element.removeAttributeNode(attribute);
            }
        }
    }
    let box = vb ? [...vb] : (root.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
    if (!vb && (box.length !== 4 || !box.every(Number.isFinite))) {
        const dimension = (name: string) => {
            const value = root.getAttribute(name) || '';
            return /^\s*\d+(?:\.\d+)?(?:px)?\s*$/.test(value) ? parseFloat(value) : NaN;
        };
        box = [0, 0, dimension('width'), dimension('height')];
    }
    if (box.length !== 4 || !box.every(Number.isFinite) || box[2] <= 0 || box[3] <= 0) {
        throw new Error('У SVG должен быть конечный viewBox с положительными шириной и высотой.');
    }
    root.setAttribute('viewBox', box.join(' '));
    inlineStyles(root, box);
    // The backend returns positions in the root coordinate system. Keep the plan's
    // transform on its own content so overlays do not receive it a second time.
    const rootTransform = root.getAttribute('transform');
    if (rootTransform !== null) {
        root.removeAttribute('transform');
        if (rootTransform.trim()) {
            const content = doc.createElementNS(SVG_NS, 'g');
            content.setAttribute('transform', rootTransform);
            while (root.firstChild) content.append(root.firstChild);
            root.append(content);
        }
    }
    return document.importNode(root, true) as unknown as SVGSVGElement;
}

export function cleanSvg(text: string): { svg: string, vb: [number, number, number, number] } {
    const svg = safeSvgElement(text);
    return {
        svg: new XMLSerializer().serializeToString(svg),
        vb: svg.getAttribute('viewBox')!.split(' ').map(Number) as [number, number, number, number]
    };
}
