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

export const dryRunNetworkChain = (data: NetworkChainRequest) =>
  Network.post('/network/chain/dry-run', data);

export const preflightNetworkChain = (data: NetworkChainRequest) =>
  Network.post('/network/chain/preflight', data);
