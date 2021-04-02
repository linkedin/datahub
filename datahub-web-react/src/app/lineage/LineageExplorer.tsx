import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';

import { Alert, Drawer } from 'antd';
import styled from 'styled-components';

import { useGetDatasetLazyQuery, useGetDatasetQuery } from '../../graphql/dataset.generated';
import { Message } from '../shared/Message';
import { Dataset } from '../../types.generated';
import { useEntityRegistry } from '../useEntityRegistry';
import CompactContext from '../shared/CompactContext';
import { Direction, EntitySelectParams, FetchedEntities, LineageExpandParams, LineageExplorerParams } from './types';
import getChildren from './utils/getChildren';
import LineageViz from './LineageViz';
import extendAsyncEntities from './utils/extendAsyncEntities';

const LoadingMessage = styled(Message)`
    margin-top: 10%;
`;

function usePrevious(value) {
    const ref = useRef();
    useEffect(() => {
        ref.current = value;
    });
    return ref.current;
}

export default function LineageExplorer() {
    const { urn } = useParams<LineageExplorerParams>();
    const previousUrn = usePrevious(urn);

    const { loading, error, data } = useGetDatasetQuery({ variables: { urn } });
    const [getUpstreamDataset, { data: upstreamDatasetData }] = useGetDatasetLazyQuery();
    const [getDownstreamDataset, { data: downstreamDatasetData }] = useGetDatasetLazyQuery();
    const [isDrawerVisible, setIsDrawVisible] = useState(false);
    const [selectedEntity, setSelectedEntity] = useState<EntitySelectParams | undefined>(undefined);
    const entityRegistry = useEntityRegistry();
    const [asyncEntities, setAsyncEntities] = useState<FetchedEntities>({});

    const maybeAddAsyncLoadedEntity = useCallback(
        ({ entity, direction, isRoot }: { entity?: Dataset; direction: Direction | null; isRoot: boolean }) => {
            console.log(direction, isRoot);
            if (entity?.urn && !asyncEntities[entity?.urn]?.fullyFetched) {
                // record that we have added this entity
                let newAsyncEntities = extendAsyncEntities(asyncEntities, entity, true);

                // add the partially fetched downstream & upstream datasets
                getChildren(entity, Direction.Downstream).forEach((downstream) => {
                    newAsyncEntities = extendAsyncEntities(newAsyncEntities, downstream.dataset, false);
                });
                getChildren(entity, Direction.Upstream).forEach((upstream) => {
                    newAsyncEntities = extendAsyncEntities(newAsyncEntities, upstream.dataset, false);
                });
                setAsyncEntities(newAsyncEntities);
            }
        },
        [asyncEntities, setAsyncEntities],
    );

    useEffect(() => {
        // currently we don't fetch all entity information we need in each async call, so when the urn we're focused on changes we need to clear our cache
        console.log({
            data,
            downstreamDatasetData,
            upstreamDatasetData,
            asyncEntities,
            setAsyncEntities,
            maybeAddAsyncLoadedEntity,
            urn,
            previousUrn,
        });
        if (urn !== previousUrn) {
            // setAsyncEntities({});
            // return;
        }
        maybeAddAsyncLoadedEntity({ entity: data?.dataset as Dataset, direction: null, isRoot: true });
        maybeAddAsyncLoadedEntity({
            entity: downstreamDatasetData?.dataset as Dataset,
            direction: Direction.Downstream,
            isRoot: false,
        });
        maybeAddAsyncLoadedEntity({
            entity: upstreamDatasetData?.dataset as Dataset,
            direction: Direction.Upstream,
            isRoot: false,
        });
    }, [
        data,
        downstreamDatasetData,
        upstreamDatasetData,
        asyncEntities,
        setAsyncEntities,
        maybeAddAsyncLoadedEntity,
        urn,
        previousUrn,
    ]);

    if (error || (!loading && !error && !data)) {
        return <Alert type="error" message={error?.message || 'Entity failed to load'} />;
    }

    return (
        <>
            {loading && <LoadingMessage type="loading" content="Loading..." />}
            {data?.dataset && (
                <div>
                    <LineageViz
                        selectedEntity={selectedEntity}
                        fetchedEntities={asyncEntities}
                        dataset={data?.dataset}
                        onEntityClick={(params: EntitySelectParams) => {
                            setIsDrawVisible(true);
                            setSelectedEntity(params);
                        }}
                        onLineageExpand={(params: LineageExpandParams) => {
                            if (params.direction === Direction.Upstream) {
                                getUpstreamDataset({ variables: { urn: params.urn } });
                            } else {
                                getDownstreamDataset({ variables: { urn: params.urn } });
                            }
                        }}
                    />
                </div>
            )}
            <Drawer
                title="Entity Overview"
                placement="left"
                closable
                onClose={() => {
                    setIsDrawVisible(false);
                    setSelectedEntity(undefined);
                }}
                visible={isDrawerVisible}
                width={425}
                mask={false}
            >
                <CompactContext.Provider value>
                    {selectedEntity && entityRegistry.renderProfile(selectedEntity.type, selectedEntity.urn)}
                </CompactContext.Provider>
            </Drawer>
        </>
    );
}
