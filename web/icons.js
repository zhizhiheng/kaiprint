const paths = {
  folder: '<path d="M3 7V5h6l2 3h10v11H3z"/><path d="M3 9h18"/>',
  save: '<path d="M4 3h13l3 3v15H4z"/><path d="M8 3v6h8V3M8 20v-7h8v7"/>',
  layout:
    '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M11 10v10"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  layers: '<path d="m3 8 9-5 9 5-9 5zM3 12l9 5 9-5M3 16l9 5 9-5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  close: '<path d="m5 5 14 14M19 5 5 19"/>',
  local:
    '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
  undo: '<path d="M3 10h11a6 6 0 0 1 0 12M3 10l5-5M3 10l5 5" transform="translate(0 -2)"/>',
  redo: '<path d="M21 8H10a6 6 0 0 0 0 12M21 8l-5-5M21 8l-5 5"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  size: '<path d="M9 3H3v6m12-6h6v6M3 15v6h6m6 0h6v-6"/>',
  TEXT: '<path d="M3 5h18M12 5v15M8 20h8"/>',
  EXIF: '<path d="M4 7h4l2-3h4l2 3h4v13H4z"/><circle cx="12" cy="13" r="3"/>',
  BRAND:
    '<path d="m12 3 8 4v7c0 4-5 6-8 8-3-2-8-4-8-8V7z"/><path d="m8 12 3 3 5-6"/>',
  LOGO: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8" cy="8" r="1"/><path d="m3 18 5-5 3 3 4-6 6 8"/>',
  LINE: '<path d="m3 18 18-12"/>',
  PALETTE:
    '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M9 5v14m6-14v14"/>',
  eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="2.5"/>',
  eyeOff:
    '<path d="m3 3 18 18M9 6c6-4 12 6 12 6s-2 3-4 4M6 7c-2 2-4 5-4 5s5 8 12 6"/>',
};
export const icon = (name) =>
  '<svg class="icon" aria-hidden="true" viewBox="0 0 24 24">' +
  (paths[name] || paths.layout) +
  "</svg>";
