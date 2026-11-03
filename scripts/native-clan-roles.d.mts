export interface NativeClanRolesFixture {
  executable: string;
  password: string;
  owner: string;
  leader: string;
  recruiter: string;
  clan: string;
  out: string;
}

export function nativeClanRoles(fixture: NativeClanRolesFixture): Promise<void>;
