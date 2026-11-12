export interface Ref {
  id: string;
  code: string;
  name: string;
}

export interface Me {
  username: string;
  displayName: string;
  tenant: Ref;
  sites: Ref[];
  roles: string[];
}

export const THEMES = ['clair', 'sombre', 'terrain'] as const;
export type Theme = (typeof THEMES)[number];

export interface Branding {
  logoUrl: string | null;
  primaryColor: string;
  theme: Theme;
}
