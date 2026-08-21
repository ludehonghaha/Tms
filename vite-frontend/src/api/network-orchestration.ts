import Network from './network';

export interface NetworkChainRequest {
  chainId: number;
  targetHost?: string;
  targetPort?: number;
  entryPort?: number;
  strictHealth?: boolean;
  checkEstimatedOutPorts?: boolean;
  requireTargetReachable?: boolean;
}

export const getNetworkGroups = () => Network.post('/network/group/list');
export const getNetworkChains = () => Network.post('/network/chain/list');
export const getNetworkTopology = () => Network.post('/network/topology');
export const getNetworkDeployments = () => Network.post('/network/deployment/list');

// These only mutate the isolated orchestration model tables. They do not create
// TMS Tunnel/Forward resources and do not send Agent runtime mutation commands.
export const saveNetworkChain = (data: any) => Network.post('/network/chain/save', data);
export const deleteNetworkChain = (id: number) => Network.post('/network/chain/delete', { id });
export const saveNetworkGroup = (data: any) => Network.post('/network/group/save', data);
export const saveNetworkGroupMember = (data: any) => Network.post('/network/group/member/save', data);

export const dryRunNetworkChain = (data: NetworkChainRequest) =>
  Network.post('/network/chain/dry-run', data);

export const preflightNetworkChain = (data: NetworkChainRequest) =>
  Network.post('/network/chain/preflight', data);
