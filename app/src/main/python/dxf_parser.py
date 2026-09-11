import ezdxf
import numpy as np

def parse_dxf(file_path):

    doc = ezdxf.readfile(file_path)
    msp = doc.modelspace()

    # Use the local function directly, not the imported one
    extracted_layers = extract_layers(file_path)
    layers = extracted_layers

    all_plan_vectors = {}
    for layer in layers['msp']:
        plan_vectors = []
        for entity in msp:
            entity_dxf = entity.dxf
            entity_type = entity.dxftype()
            if entity_dxf.layer == layer:
                vertices = process_dxf_entity_2d(entity_dxf, entity_type)
                if vertices is not None:
                    plan_vectors.append(vertices[:, :2])
        all_plan_vectors[layer] = plan_vectors

        nodes = extract_nodes(all_plan_vectors)

    plan_line_points = [
        path[:, :2].tolist()
        for plan_vectors in all_plan_vectors.values()
        for path in plan_vectors
    ]
    return plan_line_points, nodes
    

def extract_layers(file_path):

    layers = {
        'msp': {},
    }
    
    doc = ezdxf.readfile(file_path)
    msp_section = doc.modelspace()
    for entity in msp_section:
        sorting(entity, layers['msp'])

    return layers

def sorting(entity, layers):
    
    layer = entity.dxf.layer
    type = entity.dxftype()

    if layer in layers:
        layers[layer] |= {type}
    else:
        layers[layer] = {type}

    return None


def process_dxf_entity_2d(entity_dxf, entity_type):

    if entity_type == "LINE":
        return process_line_2d(entity_dxf)
    else:
        return None


def process_line_2d(entity_dxf):
    start = entity_dxf.start
    end = entity_dxf.end

    path = np.array([[start[0], start[1]], [end[0], end[1]]])
    return path


def extract_nodes(vectors):
    node_locations = []
    for plan_vectors in vectors.values():
        for path in plan_vectors:
            for vertex in path:
                node_locations.append((float(vertex[0]), float(vertex[1])))
    node_locations = list(set(node_locations))

    nodes = []
    for n, location in enumerate(node_locations):
        nodes.append({
            'id': f"V{n}",
            'x': location[0],
            'y': location[1],
        })
    return nodes