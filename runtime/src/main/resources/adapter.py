import json, math

def decide(request_json):
    request = json.loads(request_json)
    labels = request['choices']
    if not isinstance(labels, list) or not 1 <= len(labels) <= 24:
        raise ValueError('Expected 1..24 choices')
    for value in [request['context'], request['question']] + labels:
        if not isinstance(value, str) or not value.strip() or len(value) > 32768:
            raise ValueError('Invalid input string')
        if any(token in value for token in ('<<LABEL>>', '<<SEP>>', '<|', '[CLS]', '[SEP]', '[PAD]', '[MASK]', '[UNK]', '[unused')):
            raise ValueError('Reserved model token in input')
    prompt = ''.join('<<LABEL>>It is ' + label for label in labels)
    prompt += '<<LABEL>>insufficient evidence<<SEP>>Question: ' + request['question']
    prompt += '\n\nContext:\n' + request['context']
    logits = list(host.infer(prompt))[:len(labels) + 1]
    calibration = json.loads(calibration_json)
    temperature = calibration['per_k'].get(str(len(logits)), calibration['temperature'])
    scaled = [x / temperature for x in logits]
    weights = [math.exp(x - max(scaled)) for x in scaled]
    probabilities = [x / sum(weights) for x in weights]
    selected = max(range(len(logits)), key=logits.__getitem__)
    return json.dumps({'selected': selected if selected < len(labels) else None,
                       'probabilities': probabilities, 'logits': logits})
