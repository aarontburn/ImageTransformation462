import base64
import io
import os

from PIL import Image

IMAGE_FILE_KEY: str = 'image_file'
ERROR_KEY: str = 'error'


def handle_request(event, context = None):
    """
    Function #1: Image upload and validation

    Request Body {
        'image_file': The image encoded in base64.
    }
    
    Pass in a Base64 encoded image in a dictionary under key 'image_file'. Will return a
        dictionary following one of these schemas:
        
            <key>: <type> -> <description>
        Successful Response {
            'region': str -> I think when this is ran within lambda, this should be populated. Otherwise, return "NO_REGION_DATA".
            'height': int -> The height (in px i think) of the image.
            'width': str -> The width (in px i think) of the image.
            'mode': str -> The mode of the image. See https://pillow.readthedocs.io/en/stable/handbook/concepts.html
            'has_transparency_data: int -> 1 if the image has transparency data, 0 otherwise.
        }
        
        Error Response {
            'error': str -> The error converted to a string form.
        }
    
    :param event:   A dictionary containing request data (must have the key, 'image_file', with value as the image in base64 encoding.).
    :param context: 
    :returns:       A dictionary containing the response data.
    """
    if IMAGE_FILE_KEY not in event:
        return {ERROR_KEY: "Missing key-value pair associated with " + IMAGE_FILE_KEY }


    try:
        with Image.open(io.BytesIO(base64.decodebytes(event[IMAGE_FILE_KEY]))) as img:
            return {
                'region': os.environ['AWS_REGION'] if 'AWS_REGION' in os.environ else 'NO_REGION_DATA',
                'height': img.height,
                'width': img.width,
                'mode': img.mode,
                'has_transparency_data': 1 if img.has_transparency_data else 0
            }
    except Exception as e:
        return {ERROR_KEY: str(e)}

    # This might be unreachable
    return {ERROR_KEY: "Unknown error processing file."}


# Some testing code.
if __name__ == '__main__':
    print("\nPWD: " + os.getcwd())
    image_name = 'sample image.jpg'
    with open('../sample images/' + image_name, 'rb') as f:
        event_obj = {IMAGE_FILE_KEY: base64.b64encode(f.read())}
        print(handle_request(event_obj))
