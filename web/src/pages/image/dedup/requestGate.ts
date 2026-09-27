export interface RequestToken {
  scope: string;
  target: string;
  version: number;
}

export function createLatestRequestGate() {
  const versions = new Map<string, number>();
  const targets = new Map<string, string>();

  return {
    begin(scope: string, target: string): RequestToken {
      const version = (versions.get(scope) || 0) + 1;
      versions.set(scope, version);
      targets.set(scope, target);
      return { scope, target, version };
    },
    accepts(token: RequestToken) {
      return versions.get(token.scope) === token.version && targets.get(token.scope) === token.target;
    },
    invalidate(scope: string) {
      versions.set(scope, (versions.get(scope) || 0) + 1);
      targets.delete(scope);
    }
  };
}
